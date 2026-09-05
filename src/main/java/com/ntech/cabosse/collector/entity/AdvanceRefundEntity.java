package com.ntech.cabosse.collector.entity;

import com.ntech.cabosse.reception.entity.PaymentMethod;
import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Règlement du reliquat d'avance créditeur d'un délégué (épic magasin,
 * CE-187). Tenant-scopé (collection {@code advance_refunds}).
 *
 * <p>Quand les livraisons dépassent l'avance, le compte du délégué devient
 * créditeur : la coopérative lui doit la différence. Le document trace le
 * circuit demandé par l'expert : la caissière demande, le Directeur paie
 * ou reporte, et le paiement débite le compte d'avance contre la
 * trésorerie du moyen réel, chèque en banque ou pièce de caisse.</p>
 *
 * <p>Le montant demandé est contrôlé au dépôt contre le solde du compte
 * courant de campagne, et recontrôlé au paiement : une livraison saisie
 * entre-temps peut avoir changé le solde, et la caisse ne sort jamais
 * plus que ce que le compte dit.</p>
 */
public class AdvanceRefundEntity {

    @BsonId
    public UUID id;

    /** Référence affichable {@code DAP-DEL-YYYY-NNNN}, le nom de l'expert. */
    public String ref;

    public UUID delegateSupplierId;
    public String delegateName;

    public UUID campaignId;
    public Integer campaignYear;

    /** Ce que la caissière demande à sortir. */
    public BigDecimal amount;

    /**
     * Montant accordé par l'approbateur (V2 : « Oui/Non/Partiel »). Null :
     * la demande est accordée telle quelle. C'est lui qui sort de la
     * caisse quand il existe.
     */
    public BigDecimal approvedAmount;

    /** Solde créditeur du compte au moment de la demande, pour l'écran. */
    public BigDecimal creditBalanceAtRequest;

    public String notes;

    public AdvanceRefundStatus status = AdvanceRefundStatus.PENDING_APPROVAL;

    public Instant requestedAt;
    public String requestedByEmail;

    public Instant decidedAt;
    public String decidedByEmail;
    /** Mot du Directeur, à l'approbation comme au report. */
    public String decisionNote;

    // ─── Paiement ───
    public Instant paidAt;
    public String paidByEmail;
    public PaymentMethod paymentMethod;
    public UUID bankAccountId;
    public String paymentRef;
    public BigDecimal bankFees;
    /** Mot de la caissière au paiement : son accusé de l'avis favorable. */
    public String paymentNote;
    public String pieceRef;

    public Instant createdAt;
    public Instant updatedAt;
    public long version = 0L;

    /** Ce qui sort réellement : l'accordé quand il existe, sinon le demandé. */
    public BigDecimal effectiveAmount() {
        return approvedAmount != null ? approvedAmount : amount;
    }

    public AdvanceRefundEntity() {}
}
