package com.ntech.cabosse.producerpayment.entity;

import com.ntech.cabosse.reception.entity.PaymentMethod;
import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Règlement versé à un fournisseur, rattaché aux livraisons qu'il solde.
 * Tenant-scopé ({@code producer_payments}).
 *
 * <p>La coopérative ne paie pas toujours une livraison d'un coup : un
 * délégué qui apporte pour quarante millions peut être réglé en trois
 * fois, à des semaines d'intervalle, parce que d'autres attendent aussi.
 * Sans rattachement explicite, plus personne ne sait quelle fraction de
 * quelle livraison a été honorée.</p>
 *
 * <p>Un règlement porte donc des lignes, une par livraison qu'il touche.
 * C'est ce qui permet de répondre à la seule question qui compte au
 * comptoir : que reste-t-il dû sur cette livraison-là.</p>
 */
public class ProducerPaymentEntity {

    @BsonId
    public UUID id;

    /** Référence affichable {@code REG-YYYY-NNNN}. Unique par tenant. */
    public String ref;

    public LocalDate date;

    public ProducerPaymentBeneficiary beneficiaryKind;

    /** Producteur bénéficiaire, quand il est payé en direct. */
    public UUID memberId;
    /** Délégué bénéficiaire, quand la livraison passe par lui. */
    public UUID delegateSupplierId;
    /** Nom du bénéficiaire, figé au règlement. */
    public String beneficiaryName;

    public BigDecimal totalAmountFcfa;

    public PaymentMethod paymentMethod;
    public String paymentRef;

    /**
     * Frais bancaires du règlement. À la charge de la structure : ils
     * n'entrent pas dans le montant réglé et ne soldent donc aucune dette
     * envers le bénéficiaire.
     */
    public java.math.BigDecimal bankFeesFcfa;

    public List<Allocation> allocations = new ArrayList<>();

    /** Pièce comptable du règlement. */
    public String pieceRef;

    public String notes;
    /**
     * Campagne de rattachement, déduite de {@link #date}. Nulle quand aucune
     * campagne ne couvre la date et qu'aucune n'est ouverte.
     */
    public UUID campaignId;

    /** Année de la campagne, dénormalisée pour les regroupements. */
    public Integer campaignYear;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;
    public String createdByEmail;

    /**
     * Compteur d'écritures. <strong>Ce n'est pas un verrou</strong> : aucune
     * mise à jour ne le vérifie. La concurrence est traitée autrement sur
     * cette entité (le règlement n'est pas modifié après création). Ne pas s'y fier pour détecter une écriture
     * concurrente.
     */
    public long version = 0L;

    /** Part du règlement affectée à une livraison précise. */
    public static class Allocation {
        public UUID purchaseId;
        public String purchaseRef;
        public LocalDate purchaseDate;
        /** Montant dû par la coopérative sur cette livraison. */
        public BigDecimal amountDueFcfa;
        /** Ce que ce règlement y affecte. */
        public BigDecimal amountFcfa;
        /** Reste à payer sur la livraison après ce règlement. */
        public BigDecimal remainingAfterFcfa;
    }

    public ProducerPaymentEntity() {}
}
