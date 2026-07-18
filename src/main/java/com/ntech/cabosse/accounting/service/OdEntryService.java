package com.ntech.cabosse.accounting.service;

import com.ntech.cabosse.accounting.entity.ChartOfAccountsEntity;
import com.ntech.cabosse.accounting.entity.JournalEntry;
import com.ntech.cabosse.accounting.entity.JournalPieceEntity;
import com.ntech.cabosse.accounting.entity.OdDraftEntity;
import com.ntech.cabosse.accounting.entity.PostingSourceType;
import com.ntech.cabosse.accounting.repository.ChartOfAccountsRepository;
import com.ntech.cabosse.accounting.repository.OdDraftRepository;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
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
    @Inject ChartOfAccountsRepository chart;
    @Inject AccountingService accounting;
    @Inject IdGenerator idGenerator;
    @Inject TenantContext tenantContext;
    @Inject JsonWebToken jwt;

    public record OdLineInput(String account, String libelle,
                              java.math.BigDecimal debitFcfa,
                              java.math.BigDecimal creditFcfa) {}

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
        requireDraft(e, "Seul un brouillon peut être modifié");
        requireHeader(date, libelle);
        e.date = date;
        e.libelle = libelle.trim();
        e.entries = toEntries(lines);
        e.updatedAt = Instant.now();
        drafts.replace(e);
        return e;
    }

    public void delete(UUID id) {
        OdDraftEntity e = loadOrFail(id);
        requireDraft(e, "Seul un brouillon peut être supprimé");
        drafts.delete(id);
    }

    /**
     * Valide le brouillon : contrôles complets puis pièce immuable au
     * journal (source {@code MANUAL_ENTRY}, idempotente sur l'id du
     * brouillon — une revalidation ne double pas la pièce).
     */
    public OdDraftEntity validate(UUID id) {
        OdDraftEntity e = loadOrFail(id);
        requireDraft(e, "Ce brouillon est déjà validé");

        if (e.entries == null || e.entries.size() < 2) {
            throw new BusinessException("Au moins deux lignes sont requises (un débit et un crédit).");
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
                    throw new BusinessException(
                            "Compte « " + line.syscohadaAccount + " » absent du plan comptable.");
                }
            }
        }

        Optional<JournalPieceEntity> piece = accounting.postPiece(new PostingRequest(
                e.date,
                PostingSourceType.MANUAL_ENTRY,
                e.id,
                "OD",
                e.libelle,
                e.entries
        ));

        e.status = OdDraftEntity.STATUS_VALIDATED;
        e.validatedAt = Instant.now();
        e.validatedBy = safeUserId();
        piece.ifPresent(p -> e.pieceRef = p.ref);
        drafts.replace(e);
        return e;
    }

    // ─── Internals ──────────────────────────────────────────────────

    private static void requireHeader(LocalDate date, String libelle) {
        if (date == null) throw new BusinessException("Date comptable requise.");
        if (libelle == null || libelle.isBlank()) throw new BusinessException("Libellé requis.");
    }

    private static void requireDraft(OdDraftEntity e, String message) {
        if (!OdDraftEntity.STATUS_DRAFT.equals(e.status)) {
            throw new BusinessException(message + " (statut actuel : " + e.status + ").");
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
            boolean hasDebit = line.debitFcfa() != null && line.debitFcfa().signum() != 0;
            boolean hasCredit = line.creditFcfa() != null && line.creditFcfa().signum() != 0;
            if (!hasDebit && !hasCredit) continue; // ligne vide ignorée
            if (hasDebit && hasCredit) {
                throw new BusinessException(
                        "Une ligne porte soit un débit, soit un crédit, jamais les deux (compte "
                                + line.account() + ").");
            }
            if ((hasDebit && line.debitFcfa().signum() < 0)
                    || (hasCredit && line.creditFcfa().signum() < 0)) {
                throw new BusinessException("Montant négatif interdit (compte " + line.account() + ").");
            }
            if (line.account() == null || line.account().isBlank()) {
                throw new BusinessException("Compte requis sur chaque ligne montée.");
            }
            String label = line.libelle() != null && !line.libelle().isBlank()
                    ? line.libelle().trim() : "OD";
            entries.add(hasDebit
                    ? JournalEntry.debit(line.account().trim(), label, line.debitFcfa())
                    : JournalEntry.credit(line.account().trim(), label, line.creditFcfa()));
        }
        return entries;
    }

    private OdDraftEntity loadOrFail(UUID id) {
        return drafts.findById(id)
                .orElseThrow(() -> new NotFoundException("OD " + id + " introuvable."));
    }

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }
}
