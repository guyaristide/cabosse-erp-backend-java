package com.ntech.cabosse.eudr.service;

import com.ntech.cabosse.agriculture.parcel.entity.ParcelEntity;
import com.ntech.cabosse.agriculture.parcel.repository.ParcelRepository;
import com.ntech.cabosse.eudr.entity.DeforestationAlertEntity;
import com.ntech.cabosse.eudr.entity.DeforestationAlertStatus;
import com.ntech.cabosse.eudr.entity.DeforestationSeverity;
import com.ntech.cabosse.eudr.repository.DeforestationAlertRepository;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Service de surveillance déforestation.
 *
 * <p><strong>MVP — placeholder</strong> : la méthode {@link #checkParcel}
 * ne fait pas encore d'appel à une API satellite (GFW / Copernicus).
 * Elle se contente d'enregistrer un check sans alerte. L'intégration
 * réelle est planifiée en Phase 7 lorsqu'une clé API GFW sera
 * provisionnée pour le tenant. En attendant, les alertes peuvent être
 * saisies manuellement via {@link #recordManualAlert}.</p>
 *
 * <p>Pourquoi placeholder maintenant : la structure des entités, les
 * endpoints REST et le frontend doivent fonctionner pour permettre aux
 * tenants de saisir manuellement les alertes remontées par audit
 * externe ou prestataire SIG (Vivid Economics, IIASA…). Le branchement
 * automatique se fera plus tard sans toucher au modèle.</p>
 */
@ApplicationScoped
public class DeforestationCheckService {

    @Inject DeforestationAlertRepository alerts;
    @Inject ParcelRepository parcels;
    @Inject IdGenerator idGenerator;
    @Inject TenantContext tenantContext;
    @Inject JsonWebToken jwt;

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }

    public long count(DeforestationAlertStatus statusFilter, UUID parcelId) {
        return alerts.countSearch(statusFilter, parcelId);
    }

    public List<DeforestationAlertEntity> list(DeforestationAlertStatus statusFilter,
                                               UUID parcelId, int skip, int limit) {
        return alerts.search(statusFilter, parcelId, skip, limit);
    }

    /**
     * Saisie manuelle d'une alerte (typiquement par l'agronome après
     * remontée d'un prestataire SIG ou audit terrain).
     */
    public DeforestationAlertEntity recordManualAlert(UUID parcelId,
                                                     LocalDate detectedAt,
                                                     DeforestationSeverity severity,
                                                     BigDecimal areaHaImpacted,
                                                     String sourceReference,
                                                     String notes) {
        ParcelEntity parcel = parcels.findById(parcelId)
                .orElseThrow(() -> new NotFoundException(Messages.msg("m.agr-parcel-not-found", parcelId)));
        if (severity == null) throw new BusinessException(Messages.msg("m.eud-severity-required"));
        DeforestationAlertEntity alert = new DeforestationAlertEntity();
        alert.id = idGenerator.newId();
        alert.parcelId = parcel.id;
        alert.parcelCode = parcel.code;
        alert.parcelName = parcel.name;
        alert.detectedAt = detectedAt != null ? detectedAt : LocalDate.now();
        alert.severity = severity;
        alert.areaHaImpacted = areaHaImpacted;
        alert.sourceProvider = "MANUAL";
        alert.sourceReference = sourceReference != null ? sourceReference.trim() : null;
        alert.status = DeforestationAlertStatus.NEW;
        alert.notes = notes != null && !notes.isBlank() ? notes.trim() : null;
        alert.createdAt = Instant.now();
        alert.createdBy = safeUserId();
        alert.createdByEmail = actor();
        alerts.insert(alert);
        return alert;
    }

    public DeforestationAlertEntity transitionStatus(UUID alertId,
                                                    DeforestationAlertStatus targetStatus,
                                                    String remediationAction) {
        DeforestationAlertEntity alert = alerts.findById(alertId)
                .orElseThrow(() -> new NotFoundException(Messages.msg("m.eud-alert-not-found", alertId)));
        alert.status = targetStatus;
        if (targetStatus == DeforestationAlertStatus.RESOLVED) {
            alert.remediationAction = remediationAction != null ? remediationAction.trim() : null;
            alert.resolvedAt = Instant.now();
            alert.resolvedByEmail = actor();
        }
        alerts.replace(alert);
        return alert;
    }

    /**
     * Placeholder pour l'analyse satellite. Au MVP : insère un enregistrement
     * sans alerte (succès factice). Quand l'API GFW sera intégrée, cette
     * méthode appellera réellement le service externe et créera une alerte
     * si la couverture forestière a régressé depuis le 31/12/2020.
     */
    public CheckResult checkParcel(UUID parcelId) {
        ParcelEntity parcel = parcels.findById(parcelId)
                .orElseThrow(() -> new NotFoundException(Messages.msg("m.agr-parcel-not-found", parcelId)));
        // TODO Phase 7 : appel API GFW (GLAD-L, RADD) + Hansen baseline
        // pour comparer la couverture pré/post 2020. Retourner une alerte
        // si perte > 5% détectée.
        return new CheckResult(parcel.id, parcel.code, true, "Placeholder MVP : intégration API GFW en Phase 7.");
    }

    public record CheckResult(UUID parcelId, String parcelCode, boolean ok, String message) {}
}
