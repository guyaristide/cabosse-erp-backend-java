package com.ntech.cabosse.me.service;

import com.ntech.cabosse.me.dto.UpdateTenantPreferencesPayloadDto;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.dto.TenantPreferencesDto;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.entity.TenantPreferences;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gestion des préférences opérationnelles du tenant courant.
 *
 * <p>Endpoint dédié à l'administration tenant — distinct de
 * {@link com.ntech.cabosse.tenant.service.TenantUpdateService} qui
 * vit côté super-admin plateforme et qui réécrit la fiche complète.
 * Ici on accepte un PATCH partiel (chaque champ null = ne pas
 * modifier), ce qui correspond à l'usage tenant : éditer un seul
 * flag (ex. {@code vatRecoverable}) sans toucher le reste.</p>
 *
 * <p>Au MVP seul {@code vatRecoverable} est exposé en édition. Les
 * autres préférences (currency, language, timezone) restent verrouillées
 * au back-office plateforme jusqu'à ce que la fiche tenant côté admin
 * tenant les ouvre.</p>
 */
@ApplicationScoped
public class TenantPreferencesService {

    @Inject TenantContext tenantContext;
    @Inject TenantRepository tenants;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    public TenantPreferencesDto get() {
        TenantEntity t = loadCurrentTenant();
        TenantPreferences p = t.preferences != null ? t.preferences : new TenantPreferences();
        return new TenantPreferencesDto(
                p.currency, p.language, p.timezone, p.vatRecoverable(),
                p.postMemberCapitalEntries(), p.memberCapitalAccount(),
                p.postStockTransferEntries(),
                p.inventoryAlertThresholdPct(), p.inventoryAlertThresholdFcfa(),
                p.periodReopenPolicy()
        );
    }

    /**
     * Met à jour les préférences éditables. Sémantique patch — un champ
     * absent ne change rien. Journalise les diffs effectifs (utile pour
     * tracer un changement de règle TVA qui impacte tous les BC futurs).
     */
    @Transactional
    public TenantPreferencesDto update(UpdateTenantPreferencesPayloadDto payload) {
        TenantEntity t = loadCurrentTenant();
        if (t.preferences == null) {
            t.preferences = new TenantPreferences();
        }
        Map<String, Object> diffs = new LinkedHashMap<>();

        boolean currentVat = t.preferences.vatRecoverable();
        if (payload.vatRecoverable() != null
                && payload.vatRecoverable() != currentVat) {
            diffs.put("vatRecoverable",
                    Map.of("from", currentVat, "to", payload.vatRecoverable()));
            t.preferences.vatRecoverable = payload.vatRecoverable();
        }

        if (payload.postMemberCapitalEntries() != null
                && payload.postMemberCapitalEntries() != t.preferences.postMemberCapitalEntries()) {
            diffs.put("postMemberCapitalEntries", Map.of(
                    "from", t.preferences.postMemberCapitalEntries(),
                    "to", payload.postMemberCapitalEntries()));
            t.preferences.postMemberCapitalEntries = payload.postMemberCapitalEntries();
        }
        if (payload.memberCapitalAccount() != null && !payload.memberCapitalAccount().isBlank()
                && !payload.memberCapitalAccount().equals(t.preferences.memberCapitalAccount())) {
            diffs.put("memberCapitalAccount", Map.of(
                    "from", t.preferences.memberCapitalAccount(),
                    "to", payload.memberCapitalAccount()));
            t.preferences.memberCapitalAccount = payload.memberCapitalAccount().trim();
        }
        if (payload.postStockTransferEntries() != null
                && payload.postStockTransferEntries() != t.preferences.postStockTransferEntries()) {
            diffs.put("postStockTransferEntries", Map.of(
                    "from", t.preferences.postStockTransferEntries(),
                    "to", payload.postStockTransferEntries()));
            t.preferences.postStockTransferEntries = payload.postStockTransferEntries();
        }
        if (payload.inventoryAlertThresholdPct() != null
                && payload.inventoryAlertThresholdPct()
                        .compareTo(t.preferences.inventoryAlertThresholdPct()) != 0) {
            diffs.put("inventoryAlertThresholdPct", Map.of(
                    "from", t.preferences.inventoryAlertThresholdPct(),
                    "to", payload.inventoryAlertThresholdPct()));
            t.preferences.inventoryAlertThresholdPct = payload.inventoryAlertThresholdPct();
        }
        if (payload.inventoryAlertThresholdFcfa() != null
                && payload.inventoryAlertThresholdFcfa()
                        .compareTo(t.preferences.inventoryAlertThresholdFcfa()) != 0) {
            diffs.put("inventoryAlertThresholdFcfa", Map.of(
                    "from", t.preferences.inventoryAlertThresholdFcfa(),
                    "to", payload.inventoryAlertThresholdFcfa()));
            t.preferences.inventoryAlertThresholdFcfa = payload.inventoryAlertThresholdFcfa();
        }
        if (payload.periodReopenPolicy() != null && !payload.periodReopenPolicy().isBlank()
                && !payload.periodReopenPolicy().equals(t.preferences.periodReopenPolicy())) {
            diffs.put("periodReopenPolicy", Map.of(
                    "from", t.preferences.periodReopenPolicy(),
                    "to", payload.periodReopenPolicy()));
            t.preferences.periodReopenPolicy = payload.periodReopenPolicy();
        }

        if (!diffs.isEmpty()) {
            t.updatedAt = Instant.now();
            tenants.update(t);

            audit.event(AuditEventType.TENANT_UPDATED)
                    .actorEmail(actor())
                    .target("tenant", t.id.toString(), t.name)
                    .tenant(t.id, t.name)
                    .description("Préférences tenant mises à jour (" + String.join(", ", diffs.keySet()) + ")")
                    .payload(Map.of("diffs", diffs))
                    .record();
        }

        return get();
    }

    private TenantEntity loadCurrentTenant() {
        TenantEntity t = tenants.findById(tenantContext.tenantId());
        if (t == null) {
            throw new NotFoundException("Tenant courant introuvable.");
        }
        return t;
    }
}
