package com.ntech.cabosse.accounting.service;

import com.ntech.cabosse.accounting.dto.TvaDeclarationDto;
import com.ntech.cabosse.accounting.entity.TvaDeclarationEntity;
import com.ntech.cabosse.accounting.entity.TvaDeclarationStatus;
import com.ntech.cabosse.accounting.repository.TvaDeclarationRepository;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * Workflow de la déclaration TVA mensuelle :
 * <ol>
 *   <li>État A_PREPARER : implicite, aucune persistance. Montants calculés
 *       à la volée par {@link AccountingQueryService}.</li>
 *   <li>L'utilisateur déclenche {@link #markReady(String, BigDecimal, BigDecimal)}
 *       avec les montants snapshots → entité persistée en PRET_A_DEPOSER.</li>
 *   <li>Au dépôt effectif, {@link #markDeposed(String, String, LocalDate, String)}
 *       passe en DEPOSE avec n° de déclaration et date.</li>
 * </ol>
 *
 * <p>Échéance TVA Côte d'Ivoire : le 15 du mois suivant la période.</p>
 */
@ApplicationScoped
public class TvaDeclarationService {

    private static final int DUE_DAY = 15;

    @Inject TvaDeclarationRepository repo;
    @Inject IdGenerator idGenerator;
    @Inject JsonWebToken jwt;

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    /** Récupère la déclaration persistée, si elle existe. */
    public Optional<TvaDeclarationEntity> findByYearMonth(String yearMonth) {
        return repo.findByYearMonth(yearMonth);
    }

    /** Historique des dernières déclarations (DTO). */
    public List<TvaDeclarationDto> listRecent(int limit) {
        return repo.listRecent(Math.max(1, Math.min(limit, 36))).stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Verrouille les chiffres du mois et passe en PRET_A_DEPOSER. Les
     * montants snapshot sont fournis par l'appelant (typiquement
     * {@code AccountingQueryService.computeTvaDeclaration}) pour ne pas
     * dupliquer la logique d'agrégation.
     */
    public TvaDeclarationEntity markReady(String yearMonth,
                                          BigDecimal collectedFcfa,
                                          BigDecimal deductibleFcfa) {
        YearMonth ym = parseYearMonth(yearMonth);
        Optional<TvaDeclarationEntity> existing = repo.findByYearMonth(yearMonth);
        if (existing.isPresent() && existing.get().status == TvaDeclarationStatus.DEPOSE) {
            throw new BusinessException(
                    "Déclaration " + yearMonth + " déjà déposée : verrouillage refusé.");
        }
        TvaDeclarationEntity e = existing.orElseGet(TvaDeclarationEntity::new);
        boolean isNew = e.id == null;
        if (isNew) {
            e.id = idGenerator.newId();
            e.createdAt = Instant.now();
        }
        e.yearMonth = yearMonth;
        e.periodStart = ym.atDay(1);
        e.periodEnd = ym.atEndOfMonth();
        e.dueDate = ym.plusMonths(1).atDay(DUE_DAY);
        e.collectedFcfa = collectedFcfa;
        e.deductibleFcfa = deductibleFcfa;
        e.toPayFcfa = collectedFcfa.subtract(deductibleFcfa);
        e.status = TvaDeclarationStatus.PRET_A_DEPOSER;
        e.updatedAt = Instant.now();
        if (isNew) repo.insert(e); else repo.replace(e);
        return e;
    }

    public TvaDeclarationEntity markDeposed(String yearMonth, String depositedNumber,
                                            LocalDate depositedAt, String notes) {
        TvaDeclarationEntity e = repo.findByYearMonth(yearMonth)
                .orElseThrow(() -> new NotFoundException(
                        "Déclaration " + yearMonth + " introuvable : verrouillez-la d'abord."));
        if (e.status != TvaDeclarationStatus.PRET_A_DEPOSER) {
            throw new BusinessException(
                    "Déclaration " + yearMonth + " en statut " + e.status
                            + " : dépôt impossible.");
        }
        if (depositedNumber == null || depositedNumber.isBlank()) {
            throw new BusinessException("N° de déclaration requis.");
        }
        e.depositedNumber = depositedNumber.trim();
        e.depositedAt = depositedAt != null ? depositedAt : LocalDate.now();
        e.depositedByEmail = actor();
        e.notes = notes == null || notes.isBlank() ? null : notes.trim();
        e.status = TvaDeclarationStatus.DEPOSE;
        e.updatedAt = Instant.now();
        repo.replace(e);
        return e;
    }

    private TvaDeclarationDto toDto(TvaDeclarationEntity e) {
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern(
                "MMMM yyyy", java.util.Locale.FRENCH);
        String label = capitalize(YearMonth.parse(e.yearMonth).format(fmt));
        return new TvaDeclarationDto(
                label,
                e.periodStart, e.periodEnd,
                e.collectedFcfa != null ? e.collectedFcfa : BigDecimal.ZERO,
                e.deductibleFcfa != null ? e.deductibleFcfa : BigDecimal.ZERO,
                e.toPayFcfa != null ? e.toPayFcfa : BigDecimal.ZERO,
                e.dueDate,
                e.status.name()
        );
    }

    private static YearMonth parseYearMonth(String s) {
        try { return YearMonth.parse(s); }
        catch (Exception e) {
            throw new BusinessException("Format yearMonth invalide (attendu YYYY-MM) : " + s);
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
