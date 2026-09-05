package com.ntech.cabosse.accounting.service;

import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.accounting.entity.ChartOfAccountsEntity;
import com.ntech.cabosse.accounting.entity.JournalEntry;
import com.ntech.cabosse.accounting.entity.JournalPieceEntity;
import com.ntech.cabosse.accounting.entity.OdDraftEntity;
import com.ntech.cabosse.accounting.entity.PostingSourceType;
import com.ntech.cabosse.accounting.repository.ChartOfAccountsRepository;
import com.ntech.cabosse.accounting.repository.OdDraftRepository;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Saisie manuelle d'opérations diverses (backlog CPT-07) : amortissements,
 * provisions, régularisations, à-nouveaux.
 *
 * <p>Cycle : brouillon librement modifiable (équilibre non exigé pendant
 * la saisie) puis validation qui contrôle tout (lignes, équilibre,
 * existence des comptes au plan, période ouverte via
 * {@code AccountingService.postPiece}) et crée la pièce immuable au
 * journal. La correction d'une OD validée passe par contre-passation,
 * comme toute pièce.</p>
 */
@ApplicationScoped
public class OdEntryService {

    @Inject OdDraftRepository drafts;
    @Inject com.ntech.cabosse.campaign.service.CampaignResolver campaignResolver;
    @Inject ChartOfAccountsRepository chart;
    @Inject AccountingService accounting;
    @Inject IdGenerator idGenerator;
    @Inject TenantContext tenantContext;
    @Inject JsonWebToken jwt;
    @Inject com.ntech.cabosse.analytics.repository.CostCenterRepository costCenters;
    @Inject com.ntech.cabosse.analytics.repository.ProgramRepository programs;
    @Inject com.ntech.cabosse.tenant.service.TenantPreferencesLookup preferences;

    public record OdLineInput(String account, String libelle,
                              java.math.BigDecimal debit,
                              java.math.BigDecimal credit,
                              String costCenter, String program, String project) {}

    // ─── Lecture ────────────────────────────────────────────────────

    public OdDraftEntity getById(UUID id) {
        return loadOrFail(id);
    }

    public long countSearch(String status) {
        return drafts.countSearch(status);
    }

    public List<OdDraftEntity> search(String status, int skip, int limit) {
        return drafts.search(status, skip, limit);
    }

    // ─── Cycle de vie ───────────────────────────────────────────────

    public OdDraftEntity create(LocalDate date, String libelle, List<OdLineInput> lines) {
        requireHeader(date, libelle);
        OdDraftEntity e = new OdDraftEntity();
        e.id = idGenerator.newId();
        e.date = date;
        CampaignEntity campaign = campaignResolver.resolveOptionalForDate(e.date, null);
        e.campaignId = campaign != null ? campaign.id : null;
        e.campaignYear = campaign != null ? campaign.campaignYear : null;
        e.libelle = libelle.trim();
        e.entries = toEntries(lines);
        e.status = OdDraftEntity.STATUS_DRAFT;
        e.createdAt = Instant.now();
        e.createdBy = safeUserId();
        e.createdByEmail = actor();
        e.updatedAt = e.createdAt;
        drafts.insert(e);
        return e;
    }

    public OdDraftEntity update(UUID id, LocalDate date, String libelle, List<OdLineInput> lines) {
        OdDraftEntity e = loadOrFail(id);
        requireDraft(e, "m.acc-od-draft-edit-only");
        requireHeader(date, libelle);
        e.date = date;
        CampaignEntity campaign = campaignResolver.resolveOptionalForDate(e.date, null);
        e.campaignId = campaign != null ? campaign.id : null;
        e.campaignYear = campaign != null ? campaign.campaignYear : null;
        e.libelle = libelle.trim();
        e.entries = toEntries(lines);
        e.updatedAt = Instant.now();
        drafts.replace(e);
        return e;
    }

    public void delete(UUID id) {
        OdDraftEntity e = loadOrFail(id);
        requireDraft(e, "m.acc-od-draft-delete-only");
        drafts.delete(id);
    }

    /**
     * Valide le brouillon : contrôles complets puis pièce immuable au
     * journal (source {@code MANUAL_ENTRY}, idempotente sur l'id du
     * brouillon — une revalidation ne double pas la pièce).
     */
    public OdDraftEntity validate(UUID id) {
        OdDraftEntity e = loadOrFail(id);
        requireDraft(e, "m.acc-od-draft-already-validated");

        if (e.entries == null || e.entries.size() < 2) {
            throw new BusinessException(Messages.msg("m.acc-od-two-lines-required"));
        }
        // Contrôle d'existence au plan comptable — ignoré si le plan n'est
        // pas encore seedé (tenant en cours de provisioning) : postPiece
        // garde de toute façon l'équilibre et la période.
        Set<String> known = chart.list(null).stream()
                .map((ChartOfAccountsEntity a) -> a.number)
                .collect(Collectors.toSet());
        if (!known.isEmpty()) {
            for (JournalEntry line : e.entries) {
                if (line.syscohadaAccount == null || !known.contains(line.syscohadaAccount)) {
                    throw new BusinessException(Messages.msg(
                            "m.acc-od-account-not-in-chart", line.syscohadaAccount));
                }
            }
        }

        // Imputation analytique (backlog CPT-09) : chaque centre de coût
        // renseigné doit exister et être actif (toléré si le référentiel
        // n'est pas seedé). Si le tenant l'exige, toute ligne de charge
        // (classe 6) doit porter un centre.
        java.util.Set<String> activeCenters = costCenters.activeCodes();
        boolean required = preferences.current().costCenterRequired();
        for (JournalEntry line : e.entries) {
            if (line.costCenter != null && !activeCenters.isEmpty()
                    && !activeCenters.contains(line.costCenter)) {
                throw new BusinessException(Messages.msg(
                        "m.acc-od-cost-center-unknown", line.costCenter));
            }
            if (required && line.syscohadaAccount != null
                    && line.syscohadaAccount.startsWith("6") && line.costCenter == null) {
                throw new BusinessException(Messages.msg(
                        "m.acc-od-cost-center-required", line.syscohadaAccount));
            }
            if (line.program != null) {
                var prog = programs.findByCode(line.program);
                if (prog.isEmpty() || !prog.get().active) {
                    throw new BusinessException(Messages.msg(
                            "m.acc-od-program-unknown", line.program));
                }
                if (line.project != null && prog.get().projects.stream()
                        .noneMatch(pr -> pr.active && pr.code.equals(line.project))) {
                    throw new BusinessException(Messages.msg(
                            "m.acc-od-project-not-in-program", line.project, line.program));
                }
            }
        }

        // Verrou atomique DRAFT vers VALIDATED : le perdant d'un double
        // clic s'arrête avant le posting (la pièce est de toute façon
        // idempotente, mais le statut ne doit pas être écrit deux fois).
        if (!drafts.tryMarkValidated(e.id)) {
            throw new BusinessException(Messages.msg(
                    "m.acc-od-draft-already-validated", OdDraftEntity.STATUS_VALIDATED));
        }

        Optional<JournalPieceEntity> piece;
        try {
            piece = accounting.postPiece(new PostingRequest(
                    e.date,
                    PostingSourceType.MANUAL_ENTRY,
                    e.id,
                    "OD",
                    e.libelle,
                    e.entries
            ));
        } catch (RuntimeException ex) {
            // Le posting a refusé (déséquilibre, période close…) : on rend
            // le verrou pour que le brouillon reste corrigeable.
            drafts.revertToDraft(e.id);
            throw ex;
        }

        e.status = OdDraftEntity.STATUS_VALIDATED;
        e.validatedAt = Instant.now();
        e.validatedBy = safeUserId();
        piece.ifPresent(p -> e.pieceRef = p.ref);
        drafts.replace(e);
        return e;
    }

    // ─── Internals ──────────────────────────────────────────────────

    private static void requireHeader(LocalDate date, String libelle) {
        if (date == null) throw new BusinessException(Messages.msg("m.acc-od-date-required"));
        if (libelle == null || libelle.isBlank()) {
            throw new BusinessException(Messages.msg("m.acc-od-label-required"));
        }
    }

    /** {@code messageKey} porte le refus complet, statut courant en {0}. */
    private static void requireDraft(OdDraftEntity e, String messageKey) {
        if (!OdDraftEntity.STATUS_DRAFT.equals(e.status)) {
            throw new BusinessException(Messages.msg(messageKey, e.status));
        }
    }

    /**
     * Convertit la saisie en lignes de pièce. Forme de chaque ligne
     * contrôlée dès la sauvegarde (exactement un de débit/crédit,
     * strictement positif) ; l'équilibre global attendra la validation.
     */
    private static List<JournalEntry> toEntries(List<OdLineInput> lines) {
        List<JournalEntry> entries = new ArrayList<>();
        if (lines == null) return entries;
        for (OdLineInput line : lines) {
            boolean hasDebit = line.debit() != null && line.debit().signum() != 0;
            boolean hasCredit = line.credit() != null && line.credit().signum() != 0;
            if (!hasDebit && !hasCredit) continue; // ligne vide ignorée
            if (hasDebit && hasCredit) {
                throw new BusinessException(Messages.msg(
                        "m.acc-od-debit-or-credit", line.account()));
            }
            if ((hasDebit && line.debit().signum() < 0)
                    || (hasCredit && line.credit().signum() < 0)) {
                throw new BusinessException(Messages.msg("m.acc-od-negative-amount", line.account()));
            }
            if (line.account() == null || line.account().isBlank()) {
                throw new BusinessException(Messages.msg("m.acc-od-account-required"));
            }
            String label = line.libelle() != null && !line.libelle().isBlank()
                    ? line.libelle().trim() : "OD";
            JournalEntry entry = hasDebit
                    ? JournalEntry.debit(line.account().trim(), label, line.debit())
                    : JournalEntry.credit(line.account().trim(), label, line.credit());
            entries.add(entry.costCenter(line.costCenter()).program(line.program(), line.project()));
        }
        return entries;
    }

    private OdDraftEntity loadOrFail(UUID id) {
        return drafts.findById(id)
                .orElseThrow(() -> new NotFoundException(Messages.msg("m.acc-od-not-found", id)));
    }

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }
}
