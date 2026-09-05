package com.ntech.cabosse.accounting.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Exercice comptable arrêté (collection {@code fiscal_years}) — backlog
 * CPT-12. Un document n'existe que pour un exercice déjà arrêté : tant
 * que l'exercice est ouvert, il est implicite (borné par la préférence
 * tenant {@code fiscalYearStartMonth} et l'exercice arrêté précédent).
 *
 * <p>Cycle : {@link #STATUS_ARRETE} à l'issue de l'assistant de clôture
 * (en-cours, impôt, clôture des classes 6/7/8 vers 13, snapshot des
 * états), puis {@link #STATUS_CLOTURE} quand l'assemblée a affecté le
 * résultat (13 vers capital / réserves / report à nouveau).</p>
 */
public class FiscalYearEntity {

    public static final String STATUS_ARRETE = "ARRETE";
    public static final String STATUS_CLOTURE = "CLOTURE";

    @BsonId
    public UUID id;

    /** Libellé affichable : {@code "2025"} ou {@code "2025-2026"} à cheval. */
    public String label;

    public LocalDate startDate;
    public LocalDate endDate;

    /** {@link #STATUS_ARRETE} ou {@link #STATUS_CLOTURE}. */
    public String status;

    /** Produits − charges (classes 6/7, en-cours inclus), avant impôt. */
    public BigDecimal resultBeforeTax;

    /** Impôt sur le résultat comptabilisé (891/441). Zéro si exonéré. */
    public BigDecimal tax;

    /** Résultat net après impôt — solde du compte 13 à l'arrêté. */
    public BigDecimal resultNet;

    /** Total des en-cours constatés (34/734), contre-passés à l'ouverture. */
    public BigDecimal wipTotal;

    /**
     * Snapshot officiel figé à l'arrêté : lignes du compte de résultat
     * ({@code statement = "CR"}) et du bilan ({@code statement = "BILAN"}),
     * même modèle que la déclaration TVA verrouillée.
     */
    public List<SnapshotRow> snapshot;

    /** Répartition du résultat décidée par l'assemblée (comptes classe 1). */
    public List<AllocationLine> allocations;

    /** Pièces jointes (PV d'assemblée, rapport du commissaire aux comptes…). */
    public List<Document> documents;

    public Instant arrestedAt;
    public String arrestedByEmail;
    public Instant allocatedAt;
    public String allocatedByEmail;
    public Instant createdAt;
    public Instant updatedAt;

    public static class SnapshotRow {
        /** {@code "CR"} ou {@code "BILAN"}. */
        public String statement;
        public String section;
        public String rubrique;
        public BigDecimal montant;
    }

    public static class AllocationLine {
        /** Compte SYSCOHADA de classe 1 (101, 11x, 121…). */
        public String account;
        public BigDecimal amount;
    }

    public static class Document {
        public UUID id;
        public String label;
        public String fileName;
        public String mimeType;
        public long sizeBytes;
        /** Référence du binaire dans {@code cloud_files}. */
        public UUID cloudFileId;
        public Instant uploadedAt;
    }
}
