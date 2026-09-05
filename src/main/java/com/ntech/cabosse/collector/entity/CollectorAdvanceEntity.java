package com.ntech.cabosse.collector.entity;

import com.ntech.cabosse.reception.entity.PaymentMethod;
import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Avance de fonds à un délégué collecteur (backlog ACH-02, v15). La
 * coopérative avance un montant à un délégué d'une section ; celui-ci
 * livre l'équivalent en matière première, en une ou plusieurs fois,
 * jusqu'au solde de l'avance. Tenant-scoped ({@code collector_advances}).
 */
public class CollectorAdvanceEntity {

    @BsonId
    public UUID id;

    /**
     * Référence affichable {@code DA-DEL-YYYY-NNNN}. Unique par tenant.
     *
     * <p>Les demandes émises avant le 03/09/2026 portent {@code AV-YYYY-NNNN}
     * et gardent leur forme : une référence imprimée ne se réécrit pas.</p>
     */
    public String ref;

    /** Délégué (fournisseur {@code collector=true}). */
    public UUID delegateSupplierId;
    public String delegateName;

    public UUID sectionId;
    public String sectionName;

    /** Campagne de rattachement (année, snapshot). */
    /**
     * Campagne de rattachement. FK vers {@code CampaignEntity.id} : c'est
     * elle qui porte le prix de base appliqué aux livraisons imputées sur
     * l'avance, pas une année isolée.
     */
    public UUID campaignId;

    /** Année de la campagne, dénormalisée depuis {@link #campaignId}. */
    public Integer campaignYear;

    /** Site de collecte où entrent les livraisons. */
    public UUID siteId;

    public LocalDate advanceDate;
    public BigDecimal advanceAmount;
    public PaymentMethod paymentMethod;

    /** Cumul consommé par les livraisons. */
    public BigDecimal consumedAmount = BigDecimal.ZERO;
    /** Reste à livrer = avance − consommé. */
    public BigDecimal remaining;

    public CollectorAdvanceStatus status = CollectorAdvanceStatus.PENDING_APPROVAL;

    /**
     * Vrai quand le montant a franchi le seuil imposant l'approbation de
     * l'organe de gouvernance.
     *
     * <p>Figé à la demande, comme côté crédit producteur : relever le
     * seuil ensuite ne doit pas dispenser d'approbation un dossier déjà
     * déposé. Faux quand le directeur tranche seul.</p>
     */
    public boolean governanceApprovalRequired;

    /**
     * Montant réellement approuvé, qui peut être inférieur au montant
     * sollicité quand la gouvernance ne suit pas entièrement.
     *
     * <p>C'est lui, et non {@link #advanceAmount}, qui commande tout
     * ce qui suit : les fonds remis, l'écriture, le compte courant du
     * délégué et l'imputation des livraisons. Nul tant que la demande
     * n'est pas approuvée ; voir {@link #effectiveAmount()}.</p>
     */
    public BigDecimal approvedAmount;

    /**
     * Commentaire de l'approbateur, à côté de celui de l'émetteur.
     *
     * <p>Il porte l'appréciation qui a fondé la décision, notamment quand
     * le montant accordé n'est pas celui demandé. Un montant réduit sans
     * un mot laisserait le demandeur deviner.</p>
     */
    public String approvalNote;

    /**
     * Contrepartie attendue du délégué : la quantité que le montant
     * sollicité permet d'acheter, au barème de la campagne du jour de la
     * demande.
     *
     * <p>Figée à la demande avec le prix qui l'a produite : un barème
     * relevé ensuite ne réécrit pas une contrepartie déjà convenue. Nulle
     * quand la demande ne porte aucune campagne, faute de barème ; zéro se
     * lirait comme un engagement nul.</p>
     *
     * <p>Une prévision, jamais un contrôle : elle n'est pas recalculée sur
     * le montant approuvé, et rien ne s'y compare pour bloquer. Le point
     * se fait au retour du délégué, sur ce qu'il a effectivement ramené.</p>
     */
    public BigDecimal expectedQuantity;

    /** Unité de {@link #expectedQuantity}, portée par la donnée. */
    public String expectedQuantityUnit;

    /** Prix unitaire qui a produit la contrepartie, figé avec elle. */
    public BigDecimal counterpartUnitPrice;

    // ─── Traces des trois gestes du circuit ─────────────────────────
    // Qui a demandé se lit dans createdBy. Approbation et décaissement
    // portent les leurs : sans elles, on saurait qu'une avance est sortie
    // sans savoir qui l'a décidée.

    public Instant approvedAt;
    public UUID approvedBy;
    public String approvedByEmail;

    /** Motif du refus. Exigé : un refus sans raison ne se conteste pas. */
    public String rejectionReason;
    public Instant rejectedAt;
    public UUID rejectedBy;
    public String rejectedByEmail;

    public Instant disbursedAt;
    public UUID disbursedBy;
    public String disbursedByEmail;
    /**
     * Nom de qui a exécuté le règlement, figé au décaissement.
     *
     * <p>L'état de suivi nomme la caissière. Une adresse électronique ne
     * la nomme pas, et la résoudre après coup échoue dès que le compte est
     * désactivé ou renommé.</p>
     */
    public String disbursedByName;

    /** Compte de trésorerie réellement mouvementé, désigné au décaissement. */
    public UUID bankAccountId;

    /** Référence du règlement : numéro de chèque, de virement, de transaction. */
    public String paymentRef;

    /**
     * Frais bancaires du décaissement. À la charge de la structure : ils
     * n'entrent pas dans {@link #advanceAmount} et ne pèsent donc pas
     * sur le compte courant du délégué.
     */
    public BigDecimal bankFees;

    /** Référence de la pièce comptable de l'avance. */
    public String pieceRef;

    public List<Delivery> deliveries = new ArrayList<>();

    public String notes;
    public Instant closedAt;
    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;
    public String createdByEmail;

    /** Lock optimiste. */
    public long version = 0L;

    public static class Delivery {
        public UUID id;
        public LocalDate date;
        public UUID articleId;
        public String articleCode;
        public String articleName;
        public String articleUnit;
        public BigDecimal quantity;
        public BigDecimal unitPrice;
        /** {@code quantity × unitPrice}. */
        public BigDecimal amount;
        public String movementRef;
        public String pieceRef;
        public Instant recordedAt;
    }

    /**
     * Pièces justifiant l'avance : demande signée, décision, reçu de
     * remise des fonds. Déposées à la création ou plus tard, quand le
     * papier remonte du terrain.
     */
    public java.util.List<com.ntech.cabosse.shared.storage.AttachmentRef> attachments =
            new java.util.ArrayList<>();

    /**
     * Le montant qui fait foi pour l'argent : celui qui a été approuvé,
     * ou celui demandé tant que la décision n'est pas prise.
     *
     * <p>Les dossiers antérieurs à l'approbation partielle n'ont pas de
     * montant approuvé : pour eux, les deux se confondent, et c'est
     * exact.</p>
     */
    public BigDecimal effectiveAmount() {
        return approvedAmount != null ? approvedAmount : advanceAmount;
    }

    public CollectorAdvanceEntity() {}
}
