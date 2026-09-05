package com.ntech.cabosse.membercredit.entity;

import com.ntech.cabosse.reception.entity.PaymentMethod;
import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Crédit ou avance consenti à un producteur membre, remboursé par retenues
 * sur ses livraisons. Tenant-scopé ({@code member_credits}).
 *
 * <p>La coopérative avance des fonds à ses membres pour des besoins qui
 * n'ont rien à voir avec la collecte : un moyen de transport, une toiture,
 * des intrants, une dépense de santé, des funérailles. Elle se rembourse
 * ensuite sur ce que le producteur lui livre.</p>
 *
 * <p>Deux règles portent tout le reste. D'abord, <strong>rien ne se
 * décaisse sans approbation</strong>, et l'échelon qui approuve dépend du
 * montant : en dessous d'un seuil, la direction tranche seule ; au dessus,
 * l'organe de gouvernance doit approuver. Ensuite, <strong>la retenue est
 * décidée, jamais automatique</strong> : c'est une personne qui fixe, à
 * chaque livraison, ce qu'elle prélève, parce que la capacité du
 * producteur à supporter la retenue s'apprécie au cas par cas.</p>
 *
 * <p>Distinct de l'avance au délégué collecteur ({@code CollectorAdvance})
 * qui finance la collecte elle-même et se suit en compte courant.</p>
 */
public class MemberCreditEntity {

    @BsonId
    public UUID id;

    /** Référence affichable {@code CRE-YYYY-NNNN}. Unique par tenant. */
    public String ref;

    public MemberCreditKind kind;

    // ─── Bénéficiaire (snapshot de la fiche membre) ───
    public UUID memberId;
    public String memberName;
    public String memberCode;
    public UUID sectionId;
    public String sectionName;

    /** Campagne de rattachement, pour les états de fin de campagne. */
    public UUID campaignId;
    public String campaignLabel;

    /** Objet du financement, tel que déclaré (saisie libre ou référentiel). */
    public String purpose;

    public BigDecimal amount;

    /**
     * Contrepartie attendue du producteur : la quantité qu'il livrera en
     * face de la somme avancée.
     *
     * <p>Proposée au barème de la campagne, saisie par la coopérative, et
     * figée avec le prix qui l'a proposée. Une prévision, jamais un
     * contrôle : rien ne s'y compare pour bloquer une livraison, le point
     * se fait au retour.</p>
     */
    public BigDecimal expectedQuantity;

    /** Unité de {@link #expectedQuantity}, portée par la donnée. */
    public String expectedQuantityUnit;

    /** Prix unitaire qui a proposé la contrepartie, figé avec elle. */
    public BigDecimal counterpartUnitPrice;


    public LocalDate requestedAt;
    public String requestedByEmail;

    public MemberCreditStatus status = MemberCreditStatus.PENDING_APPROVAL;

    /**
     * Vrai quand le montant a franchi le seuil imposant l'approbation de
     * l'organe de gouvernance. Figé à la demande : relever le seuil plus
     * tard ne doit pas effacer l'exigence qui pesait sur un dossier en
     * cours.
     */
    public boolean governanceApprovalRequired;

    /**
     * Montant réellement accordé, qui peut être inférieur au montant
     * sollicité.
     *
     * <p>Le document liste « Approbation Oui/Partiel » et « Montant
     * approuvé » des deux côtés : le directeur accorde parfois moins que
     * ce que le producteur demande. C'est lui, et non le sollicité, qui
     * sort de la caisse. Nul tant que la décision n'est pas prise ; voir
     * {@link #effectiveAmount()}.</p>
     */
    public BigDecimal approvedAmount;

    public Instant approvedAt;
    public UUID approvedBy;
    public String approvedByEmail;
    public String approvalNote;

    public Instant rejectedAt;
    public String rejectedByEmail;
    public String rejectionReason;

    // ─── Décaissement ───
    public LocalDate disbursedAt;
    public UUID disbursedBy;
    public String disbursedByEmail;
    /**
     * Nom de qui a remis les fonds, figé au décaissement.
     *
     * <p>L'état de suivi nomme la caissière. Une adresse électronique ne
     * la nomme pas, et la résoudre après coup échoue dès que le compte est
     * désactivé ou renommé.</p>
     */
    public String disbursedByName;
    public PaymentMethod paymentMethod;
    public String paymentRef;

    /**
     * Frais bancaires du décaissement. À la charge de la structure : ils
     * n'entrent pas dans le montant du crédit et ne pèsent donc pas sur la
     * créance du producteur.
     */
    public java.math.BigDecimal bankFees;
    /** Pièce comptable du décaissement. */
    public String pieceRef;

    // ─── Remboursement ───
    /** Cumul retenu sur les livraisons. */
    public BigDecimal imputedAmount = BigDecimal.ZERO;
    /** Reste dû par le producteur = montant − imputé. */
    public BigDecimal remaining;

    public List<Imputation> imputations = new ArrayList<>();

    public String notes;
    public Instant settledAt;
    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;
    public String createdByEmail;

    /** Lock optimiste. */
    public long version = 0L;

    /**
     * Retenue opérée sur une livraison précise. Conserve qui a décidé et
     * de combien : c'est la trace qu'un producteur peut venir contester.
     */
    public static class Imputation {
        public UUID id;
        public UUID purchaseId;
        public String purchaseRef;
        public LocalDate date;
        public BigDecimal amount;
        public String decidedByEmail;
        public Instant decidedAt;
        public String notes;
    }

    /**
     * Pièces justifiant l'engagement : demande signée, procès-verbal du
     * conseil, pièce d'identité, reçu de décaissement. Déposées à la
     * création ou plus tard.
     */
    public java.util.List<com.ntech.cabosse.shared.storage.AttachmentRef> attachments =
            new java.util.ArrayList<>();

    /**
     * Le montant qui fait foi pour l'argent : celui accordé, ou celui
     * demandé tant que la décision n'est pas prise.
     *
     * <p>Les dossiers antérieurs à l'approbation partielle n'ont pas de
     * montant accordé : pour eux les deux se confondent, et c'est exact.</p>
     */
    public BigDecimal effectiveAmount() {
        return approvedAmount != null ? approvedAmount : amount;
    }

    public MemberCreditEntity() {}
}
