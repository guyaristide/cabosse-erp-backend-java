package com.ntech.cabosse.producerpurchase.entity;

import com.ntech.cabosse.reception.entity.PaymentMethod;
import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Reçu d'achat de matière première au producteur membre (backlog NEG-01).
 * Tenant-scopé (collection {@code producer_purchases}).
 *
 * <p>Suit le modèle du « Reçu Achat Cacao CCC » : bloc acheteur (dérivé du
 * profil coopérative à l'affichage/export), bloc producteur (snapshot de la
 * fiche membre) et bloc transaction. L'achat ne passe pas par le catalogue
 * d'articles de production : le {@link #productCode} vient de la liste des
 * produits de la coopérative, résolue vers l'{@link #articleId} matière
 * première (lien manuel) pour l'entrée stock + le CMUP.</p>
 *
 * <p>Le reçu peut être <strong>autonome</strong> (payé directement,
 * {@link #collectorAdvanceId} null) ou <strong>rattaché à une avance
 * délégué</strong> (ACH-02) : dans ce cas l'écriture impute le compte
 * d'avance (4091) au lieu de la trésorerie et l'avance est décrémentée.</p>
 */
public class ProducerPurchaseEntity {

    @BsonId
    public UUID id;

    /** Référence affichable {@code ACP-YYYY-NNNN}. Unique par tenant. */
    public String ref;

    public LocalDate date;

    // ─── Producteur (snapshot fiche membre MEM-06) ───
    public UUID memberId;
    public String producerName;
    public String producerCode;
    /** N° carte CCC producteur (code externe du membre). */
    public String producerExternalCode;
    public String village;
    public String producerPhone;
    public UUID sectionId;
    public String sectionName;

    // ─── Produit → article (lien manuel COOP-03/NEG-01) ───
    public String productCode;
    public String productLabel;
    public UUID articleId;
    public String articleCode;
    public String articleName;
    public String articleUnit;

    // ─── Contexte ───
    public UUID siteId;
    public UUID campaignId;
    public Integer campaignYear;

    // ─── Transaction ───
    public Integer nbSacs;
    public BigDecimal weightKg;
    public BigDecimal guaranteedPricePerKgFcfa;
    public BigDecimal amountFcfa;

    public PaymentMethod paymentMethod;
    public String paymentRef;

    /** Payeur : délégué de l'avance si rattaché, sinon référence choisie. */
    public UUID payerMemberId;
    public String payerName;

    /** Rattachement optionnel à une avance délégué (ACH-02). */
    public UUID collectorAdvanceId;

    // ─── Traces d'intégration ───
    public String movementRef;
    public String pieceRef;

    // ─── Audit ───
    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;
    public String createdByEmail;

    public long version = 0L;

    public ProducerPurchaseEntity() {}
}
