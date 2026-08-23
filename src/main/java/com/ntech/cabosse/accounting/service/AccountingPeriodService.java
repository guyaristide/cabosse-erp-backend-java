package com.ntech.cabosse.accounting.service;

import com.ntech.cabosse.accounting.entity.AccountingPeriodEntity;
import com.ntech.cabosse.accounting.repository.AccountingPeriodRepository;
import com.ntech.cabosse.accounting.repository.OdDraftRepository;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.ErrorCode;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.entity.TenantPreferences;
import com.ntech.cabosse.tenant.service.TenantPreferencesLookup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * Clôture et réouverture des périodes comptables mensuelles.
 *
 * <p>Règles :</p>
 * <ul>
 *   <li>Seule une période échue ou en cours peut être verrouillée (pas de
 *       clôture par anticipation d'un mois futur).</li>
 *   <li>Le verrouillage est bloquant : plus aucune pièce datée dans la
 *       période ne peut être créée ({@link #assertOpen}).</li>
 *   <li>La réouverture exige un motif, laisse le document en trace et
 *       émet un événement d'audit. Le décideur de la réouverture reste à
 *       arbitrer (backlog CPT-03) — en attendant, même rôle que la
 *       clôture (TENANT_ADMIN au niveau du contrôleur).</li>
 * </ul>
 */
@ApplicationScoped
public class AccountingPeriodService {

    @Inject AccountingPeriodRepository periods;
    @Inject OdDraftRepository odDrafts;
    @Inject com.ntech.cabosse.accounting.repository.QuarantinedPostingRepository quarantined;
    @Inject IdGenerator idGenerator;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject TenantPreferencesLookup preferences;
    @Inject JsonWebToken jwt;

    /** Refuse la comptabilisation si la période de {@code date} est verrouillée. */
    public void assertOpen(LocalDate date) {
        if (date == null) return;
        String period = YearMonth.from(date).toString();
        if (periods.isLocked(period)) {
            throw new BusinessException(ErrorCode.PERIOD_LOCKED,
                    "Période " + period + " clôturée : aucune écriture ne peut plus y être passée. "
                            + "Rouvrez la période pour corriger.");
        }
    }

    public List<AccountingPeriodEntity> list() {
        return periods.listAll();
    }

    public AccountingPeriodEntity lock(String periodRaw) {
        YearMonth period = parse(periodRaw);
        if (period.isAfter(YearMonth.now())) {
            throw new BusinessException("Impossible de clôturer un mois futur (" + period + ").");
        }
        // Contrôle préalable (CPT-06, option B) : aucune OD en brouillard
        // datée dans le mois — elles doivent être validées ou supprimées.
        long pendingOd = odDrafts.countDraftsInPeriod(period.atDay(1), period.atEndOfMonth());
        if (pendingOd > 0) {
            throw new BusinessException(
                    pendingOd + " opération(s) diverse(s) en brouillard sur " + period
                            + " : validez-les ou supprimez-les avant de clôturer.");
        }
        // Même logique pour les écritures déjà retenues sur ce mois : les
        // enfermer derrière une clôture reviendrait à les abandonner, alors
        // qu'elles attendent précisément une décision.
        long pendingQuarantine = quarantined.countPendingInPeriod(
                period.atDay(1), period.atEndOfMonth());
        if (pendingQuarantine > 0) {
            throw new BusinessException(
                    pendingQuarantine + " écriture(s) en attente de régularisation sur " + period
                            + " : traitez-les avant de clôturer.");
        }

        AccountingPeriodEntity existing = periods.findByPeriod(period.toString()).orElse(null);
        if (existing != null && AccountingPeriodEntity.STATUS_LOCKED.equals(existing.status)) {
            return existing; // idempotent
        }
        AccountingPeriodEntity e = existing != null ? existing : new AccountingPeriodEntity();
        if (existing == null) {
            e.id = idGenerator.newId();
            e.period = period.toString();
        }
        e.status = AccountingPeriodEntity.STATUS_LOCKED;
        e.lockedAt = Instant.now();
        e.lockedBy = safeUserId();
        e.lockedByEmail = actor();
        if (existing == null) periods.insert(e); else periods.replace(e);

        audit.event(AuditEventType.ACCOUNTING_PERIOD_LOCKED)
                .actorEmail(actor())
                .target("accounting_period", e.id.toString(), e.period)
                .tenant(tenantContext.tenantId(), null)
                .description("Clôture de la période comptable " + e.period)
                .record();
        return e;
    }

    public AccountingPeriodEntity reopen(String periodRaw, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("Motif de réouverture requis.");
        }
        // Politique du tenant : PLATFORM_ONLY réserve la réouverture au
        // back-office plateforme (préférence periodReopenPolicy, défaut
        // TENANT_ADMIN).
        if (TenantPreferences.REOPEN_PLATFORM_ONLY.equals(preferences.current().periodReopenPolicy())
                && !isPlatformAdmin()) {
            throw new BusinessException(
                    "La réouverture de période est réservée au support plateforme "
                            + "(politique définie par votre administration).");
        }
        YearMonth period = parse(periodRaw);
        AccountingPeriodEntity e = periods.findByPeriod(period.toString())
                .orElseThrow(() -> new BusinessException(
                        "La période " + period + " n'a jamais été clôturée."));
        if (!AccountingPeriodEntity.STATUS_LOCKED.equals(e.status)) {
            return e; // déjà rouverte — idempotent
        }
        e.status = AccountingPeriodEntity.STATUS_REOPENED;
        e.reopenedAt = Instant.now();
        e.reopenedBy = safeUserId();
        e.reopenedByEmail = actor();
        e.reopenReason = reason.trim();
        periods.replace(e);

        audit.event(AuditEventType.ACCOUNTING_PERIOD_REOPENED)
                .actorEmail(actor())
                .target("accounting_period", e.id.toString(), e.period)
                .tenant(tenantContext.tenantId(), null)
                .description("Réouverture de la période " + e.period + " : " + e.reopenReason)
                .record();
        return e;
    }

    private static YearMonth parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("Période requise (format AAAA-MM).");
        }
        try {
            return YearMonth.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException("Période invalide « " + raw + "» : format attendu AAAA-MM.");
        }
    }

    private boolean isPlatformAdmin() {
        try { return jwt.getGroups() != null && jwt.getGroups().contains(Roles.PLATFORM_ADMIN); }
        catch (Exception e) { return false; }
    }

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }
}
