package com.ntech.cabosse.settlement.entity;

import com.ntech.cabosse.producerpayment.entity.ProducerPaymentBeneficiary;
import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Une demande de règlement du solde d'un délégué ou d'un producteur.
 *
 * <p>Demandée par l'expert-comptable le 12/09/2026. Régler quelqu'un
 * était un geste unique : la caissière enregistrait le paiement et
 * l'argent sortait, sans qu'aucune décision soit demandée ni gardée en
 * trace. La comptable veut pouvoir solliciter l'accord du président ou du
 * directeur avant la sortie.</p>
 *
 * <p>Le circuit reprend celui des avances, qu'on ne duplique pas : même
 * file d'approbation, même échelon de gouvernance au-delà d'un seuil,
 * mêmes droits distincts pour demander, approuver et payer. Ce qui change
 * est le sens de l'argent : une avance part avant la livraison, un
 * règlement solde ce qui est dû après.</p>
 *
 * <p>Le <strong>montant approuvé</strong> commande l'aval. Si le dû
 * change entre la décision et le paiement, parce qu'une livraison arrive
 * entre-temps, c'est le montant accordé qui sort : sinon l'approbation ne
 * garantit rien.</p>
 */
public class SettlementRequestEntity {

    public static final String COLLECTION = "settlement_requests";

    @BsonId
    public UUID id;

    /** Numéro lisible, de la forme « DR-AAAA-NNNN ». */
    public String ref;

    public ProducerPaymentBeneficiary beneficiaryKind;
    public UUID memberId;
    public UUID delegateSupplierId;
    public String beneficiaryName;

    /** Ce que le demandeur a sollicité, au vu du dû à cet instant. */
    public BigDecimal requestedAmount;
    /** Ce que l'approbateur a accordé. Absent tant que rien n'est décidé. */
    public BigDecimal approvedAmount;

    /**
     * Le moyen par lequel le règlement doit sortir.
     *
     * <p>Il fait partie de ce qui est approuvé, pas seulement de ce qui
     * est exécuté : le pouvoir de décision n'est pas le même selon
     * l'instrument (expert-comptable, 13/09/2026). Approuver une sortie
     * de caisse puis payer par chèque contournerait la décision, aussi
     * le règlement vérifie-t-il qu'il emploie bien le moyen accordé.</p>
     */
    public com.ntech.cabosse.reception.entity.PaymentMethod paymentMethod;

    /**
     * Au-delà du second seuil, l'approbation ordinaire ne suffit pas.
     * Le moyen de règlement l'exige aussi, quel que soit le montant.
     * Figé à la demande : déplacer le seuil ou la liste des moyens
     * ensuite ne doit pas changer ce qu'une demande déjà déposée
     * exigeait.
     */
    public Boolean governanceApprovalRequired;

    public SettlementRequestStatus status = SettlementRequestStatus.PENDING_APPROVAL;

    public UUID campaignId;
    public Integer campaignYear;
    public UUID siteId;

    public LocalDate requestedOn;
    public Instant requestedAt;
    public String requestedByEmail;
    /** Ce que le demandeur explique : à quoi correspond la somme. */
    public String notes;

    public Instant decidedAt;
    public String decidedByEmail;
    /** Le mot de l'approbateur, accord comme refus. */
    public String decisionNote;

    /** Le règlement qui a soldé la demande. */
    public UUID paymentId;
    public String paymentRef;
    public Instant paidAt;

    public Instant createdAt;
    public Instant updatedAt;
    public long version;
}
