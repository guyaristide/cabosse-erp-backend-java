package com.ntech.cabosse.expense.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Dépense directe sans bon de livraison (backlog ACH-03). Enregistrement
 * immuable : la pièce comptable est générée à la création. Une correction
 * passe par contre-passation de la pièce (jamais de mutation).
 *
 * <p>Deux circuits (cf. {@link DirectExpenseKind}) partageant le même
 * moteur : {@code CONTRACT} (facture périodique d'un prestataire, TVA
 * possible, réglée par virement/prélèvement) et {@code PETTY_CASH}
 * (petite dépense réglée en espèces par le régisseur, sans TVA en règle
 * générale). Aucune réception ni mouvement de stock.</p>
 *
 * <p>Écriture : débit compte de charge (HT) + débit TVA déductible si
 * applicable / crédit compte de trésorerie (TTC) selon le mode de
 * règlement. Tenant-scopé (collection {@code direct_expenses}).</p>
 */
public class DirectExpenseEntity {

    @BsonId
    public UUID id;

    /** Référence séquentielle {@code DEP-YYYY-NNNN}. */
    public String ref;

    public DirectExpenseKind kind;

    /** Date métier de la dépense (= date de comptabilisation). */
    public LocalDate expenseDate;

    /** Prestataire / fournisseur (CONTRACT). Facultatif pour la petite caisse. */
    public UUID supplierId;
    public String supplierName;

    /** Type de dépense du référentiel, s'il a servi à résoudre le compte de charge. */
    public UUID expenseTypeId;
    public String expenseTypeName;

    /** Compte de charge SYSCOHADA débité (résolu du type de dépense ou saisi). */
    public String chargeAccount;

    /** Libellé de la dépense (objet de la facture, nature de l'achat). */
    public String label;

    /** Période couverte pour un contrat/abonnement (ex. « Juillet 2026 »). */
    public String periodLabel;

    /** Clé de répartition si la charge est indirecte (CPT-17). {@code null} = directe. */
    public String allocationKeyCode;
    public String allocationKeyName;

    public BigDecimal amountHtFcfa = BigDecimal.ZERO;
    public BigDecimal vatRatePct = BigDecimal.ZERO;
    public BigDecimal vatAmountFcfa = BigDecimal.ZERO;
    public BigDecimal amountTtcFcfa = BigDecimal.ZERO;

    /** Mode de règlement (détermine le compte de trésorerie crédité). */
    public String paymentMethod;

    /** Compte de trésorerie crédité (snapshot au moment de la comptabilisation). */
    public String treasuryAccount;

    /** Référence de la pièce au journal. */
    public String pieceRef;

    public String notes;

    /**
     * Campagne de rattachement, déduite de {@link #expenseDate}. Nulle quand aucune
     * campagne ne couvre la date et qu'aucune n'est ouverte.
     */
    public UUID campaignId;

    /** Année de la campagne, dénormalisée pour les regroupements. */
    public Integer campaignYear;

    public Instant createdAt;
    public UUID createdBy;
    public String actorEmail;

    public DirectExpenseEntity() {}
}
