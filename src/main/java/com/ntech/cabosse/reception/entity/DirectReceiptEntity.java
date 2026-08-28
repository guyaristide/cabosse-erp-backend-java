package com.ntech.cabosse.reception.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Session de <strong>réception directe</strong> = achat sans BC formel.
 * Le flux terrain typique : la personne en charge des achats reçoit
 * plusieurs producteurs sur une même journée pour <em>un même produit</em>
 * (fèves de cacao, latex, etc.), pèse, fixe le prix unitaire, paie cash
 * ou note la dette. Tenant-scopé (collection {@code direct_receipts}).
 *
 * <p>Pourquoi une entité distincte du BC : le cycle métier diffère
 * fondamentalement — pas de commande préalable, pas de transit, le
 * fournisseur arrive avec sa marchandise. Tenter d'utiliser le cycle
 * BC ici créerait du formalisme inutile.</p>
 *
 * <p>L'article est porté par la session : <strong>1 session = 1 article</strong>
 * (cf. décisions de cadrage 2026-05-21). Une journée d'appro où on
 * achète à la fois fèves et beurre = deux sessions distinctes.</p>
 */
public class DirectReceiptEntity {

    @BsonId
    public UUID id;

    /** Référence affichable {@code RD-2026-0001}. Unique par tenant. */
    public String ref;

    /** Site de réception. */
    public UUID siteId;

    /** Date de réception physique de la marchandise. */
    public LocalDate receivedDate;

    /** Email du user tenant qui a réceptionné (snapshot du JWT). */
    public String receiverEmail;

    // ─── Article unique de la session (snapshots) ───
    public UUID articleId;
    public String articleCode;
    public String articleName;
    public String articleUnit;

    /** Lignes par fournisseur (1+). */
    public List<DirectReceiptLine> lines;

    /** Somme des {@code totalLineFcfa}. Dérivé. */
    public BigDecimal subtotalHtFcfa;
    /** Somme des {@code payment.amountFcfa} des lignes payées. Dérivé. */
    public BigDecimal totalPaidFcfa;

    /**
     * Taux TVA appliqué à la session (0–100, défaut 0). Une RD adressée à
     * un producteur paysan non assujetti reste à 0 — c'est le cas le plus
     * fréquent. La présence du champ permet de gérer le cas d'une RD à un
     * fournisseur formel sans BC. Aligné sémantiquement sur
     * {@code PurchaseOrderEntity.vatRatePct}.
     */
    public BigDecimal vatRatePct;

    /**
     * Surcharge du flag tenant {@code vatRecoverable} pour cette RD.
     * null = utiliser le défaut tenant. false = TVA intégrée au CMUP
     * à la livraison stock (PU effectif = PU × (1 + vatRatePct/100)).
     *
     * <p>Symétrique de {@link com.ntech.cabosse.achats.entity.PurchaseOrderEntity#vatRecoverableOverride}.</p>
     *
     * <p>Stocké en {@code Boolean} (nullable) précisément pour distinguer
     * "non précisé / hériter du tenant" de "explicitement à false".</p>
     */
    public Boolean vatRecoverableOverride;

    /** Statut dérivé du remplissage des paiements. */
    public DirectReceiptStatus status = DirectReceiptStatus.UNPAID;

    public DirectReceiptCancellation cancellation;

    /** N° de bon de livraison de session (optionnel). */
    public String deliveryNoteRef;

    public String notes;

    /**
     * Campagne de rattachement, déduite de {@link #receivedDate}. Nulle quand aucune
     * campagne ne couvre la date et qu'aucune n'est ouverte.
     */
    public UUID campaignId;

    /** Année de la campagne, dénormalisée pour les regroupements. */
    public Integer campaignYear;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;

    /** Lock optimiste — incrémenté à chaque écriture (cf. DirectReceiptRepository.replace). */
    public long version = 0L;

    public DirectReceiptEntity() {}
}
