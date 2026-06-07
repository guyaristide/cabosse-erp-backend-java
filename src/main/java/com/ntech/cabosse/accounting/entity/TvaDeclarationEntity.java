package com.ntech.cabosse.accounting.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Déclaration de TVA mensuelle persistée. Tenant-scopée
 * (collection {@code tva_declarations}).
 *
 * <p>Identité métier : {@link #yearMonth} (ex. "2026-06") — index unique
 * en BD. Tant qu'aucune action n'est prise sur un mois, aucune ligne
 * n'existe : la lecture du dashboard agrège alors les soldes à la volée
 * et présente la déclaration en {@link TvaDeclarationStatus#A_PREPARER}.</p>
 *
 * <p>Les montants {@link #collectedFcfa} / {@link #deductibleFcfa} sont
 * <strong>snapshotés</strong> au moment du verrouillage
 * ({@link TvaDeclarationStatus#PRET_A_DEPOSER}) — toute écriture passée
 * ultérieurement sur la période ne modifie pas la déclaration figée.</p>
 */
public class TvaDeclarationEntity {

    @BsonId
    public UUID id;

    /** Format ISO YYYY-MM. Index unique. */
    public String yearMonth;

    /** Bornes incluses du mois (pour faciliter les requêtes). */
    public LocalDate periodStart;
    public LocalDate periodEnd;

    public TvaDeclarationStatus status;

    // Snapshots des montants au moment du verrouillage (PRET_A_DEPOSER).
    public BigDecimal collectedFcfa;
    public BigDecimal deductibleFcfa;
    public BigDecimal toPayFcfa;

    public LocalDate dueDate;

    // Champs renseignés au passage en DEPOSE.
    public String depositedNumber;
    public LocalDate depositedAt;
    public String depositedByEmail;
    public String notes;

    public Instant createdAt;
    public Instant updatedAt;

    public TvaDeclarationEntity() {}
}
