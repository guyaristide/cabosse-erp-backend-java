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

    /** Référence affichable {@code AV-YYYY-NNNN}. Unique par tenant. */
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
    public BigDecimal advanceAmountFcfa;
    public PaymentMethod paymentMethod;

    /** Cumul consommé par les livraisons. */
    public BigDecimal consumedAmountFcfa = BigDecimal.ZERO;
    /** Reste à livrer = avance − consommé. */
    public BigDecimal remainingFcfa;

    public CollectorAdvanceStatus status = CollectorAdvanceStatus.PENDING_APPROVAL;

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

    /** Compte de trésorerie réellement mouvementé, désigné au décaissement. */
    public UUID bankAccountId;

    /** Référence du règlement : numéro de chèque, de virement, de transaction. */
    public String paymentRef;

    /**
     * Frais bancaires du décaissement. À la charge de la structure : ils
     * n'entrent pas dans {@link #advanceAmountFcfa} et ne pèsent donc pas
     * sur le compte courant du délégué.
     */
    public BigDecimal bankFeesFcfa;

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
        public BigDecimal unitPriceFcfa;
        /** {@code quantity × unitPriceFcfa}. */
        public BigDecimal amountFcfa;
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

    public CollectorAdvanceEntity() {}
}
