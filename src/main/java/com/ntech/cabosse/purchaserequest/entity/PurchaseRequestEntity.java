package com.ntech.cabosse.purchaserequest.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Demande d'achat (backlog ACH-01). Maillon amont du circuit d'achat
 * complet de la v15 : DA soumise puis approuvée avant l'émission d'un bon
 * de commande. Tenant-scoped (collection {@code purchase_requests}).
 *
 * <p>La DA porte une estimation ; le prix négocié et la comptabilisation
 * appartiennent au BC qui en est issu (lien {@link #convertedOrderId}).</p>
 */
public class PurchaseRequestEntity {

    @BsonId
    public UUID id;

    /** Référence affichable {@code DA-YYYY-NNNN}. Unique par tenant. */
    public String ref;

    /** Site demandeur. Initialisé sur le site actif à la création. */
    public UUID siteId;

    /** Fournisseur pressenti (optionnel — précisé au plus tard à la conversion). */
    public UUID supplierId;
    public String supplierName;

    public LocalDate requestDate;
    /** Justification du besoin (texte libre). */
    public String justification;

    public List<PurchaseRequestLine> lines;

    /** Total estimé — somme des lignes. */
    public BigDecimal estimatedTotalFcfa;

    public PurchaseRequestStatus status = PurchaseRequestStatus.DRAFT;

    /** Motif de rejet, renseigné à la décision. */
    public String decisionReason;
    public Instant submittedAt;
    public Instant decidedAt;
    public String decidedByEmail;

    /** BC issu de la conversion (si {@link PurchaseRequestStatus#CONVERTED}). */
    public UUID convertedOrderId;
    public String convertedOrderRef;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;
    public String createdByEmail;

    /** Lock optimiste. */
    public long version = 0L;

    public PurchaseRequestEntity() {}
}
