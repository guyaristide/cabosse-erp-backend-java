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

    public BigDecimal amountHt = BigDecimal.ZERO;
    public BigDecimal vatRatePct = BigDecimal.ZERO;
    public BigDecimal vatAmount = BigDecimal.ZERO;
    public BigDecimal amountTtc = BigDecimal.ZERO;

    /** Mode de règlement (détermine le compte de trésorerie crédité). */
    public String paymentMethod;

    /** Compte de trésorerie crédité (snapshot au moment de la comptabilisation). */
    public String treasuryAccount;

    /** Référence de la pièce au journal. */
    public String pieceRef;

    /**
     * Le compte de tiers crédité au constat.
     *
     * <p>La dépense ne se règle plus à la saisie : elle se constate, et
     * le paiement part de la trésorerie comme pour les avances et les
     * livraisons (demandé le 03/10/2026). Le compte est celui du
     * prestataire quand il en porte un, le collectif fournisseurs sinon
     * : une petite dépense n'a pas toujours de fiche en face.</p>
     *
     * <p>Absent sur les dépenses d'avant la bascule, qui ont été réglées
     * à la saisie et n'ont jamais eu de dette à porter.</p>
     */
    public String payableAccount;

    /**
     * La décision attendue avant paiement, quand la structure en exige
     * une.
     *
     * <p>Absente, la dépense se règle directement : c'est le cas tant
     * que personne n'a posé de règle. Valider une commande auprès d'un
     * fournisseur ne vaut pas ordre de payer, et la caisse arbitre ses
     * priorités (demandé le 03/10/2026).</p>
     */
    public String approvalStatus;

    /** Le second échelon est-il requis, figé à la saisie. */
    public boolean governanceApprovalRequired;

    public java.time.Instant approvedAt;
    public String approvedByEmail;
    public java.time.Instant governanceApprovedAt;
    public String governanceApprovedByEmail;
    public String rejectionReason;

    /** Ce qui a déjà été payé sur cette dépense. */
    public BigDecimal amountPaid = BigDecimal.ZERO;

    /** Quand elle a été soldée. Null tant qu'il reste à payer. */
    public java.time.Instant settledAt;

    /**
     * Reste à payer, ou zéro pour une dépense d'avant la bascule.
     *
     * <p>Celles-là sont sorties de la caisse à la saisie : les faire
     * réapparaître dans la file à payer ferait décaisser deux fois.</p>
     */
    /**
     * La dépense peut-elle être réglée ?
     *
     * <p>Sans circuit, oui. Avec, il faut la décision, et celle du
     * second échelon quand il est requis.</p>
     */
    public boolean payable() {
        if (approvalStatus == null) return true;
        if (!"APPROVED".equals(approvalStatus)) return false;
        return !governanceApprovalRequired || governanceApprovedAt != null;
    }

    public BigDecimal remaining() {
        if (payableAccount == null) return BigDecimal.ZERO;
        BigDecimal paid = amountPaid == null ? BigDecimal.ZERO : amountPaid;
        return (amountTtc == null ? BigDecimal.ZERO : amountTtc).subtract(paid).max(BigDecimal.ZERO);
    }

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
