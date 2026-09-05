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
 * <p>Le reçu peut être <strong>autonome</strong> (payé directement sur la
 * trésorerie) ou <strong>rattaché à un délégué collecteur</strong> (ACH-02) :
 * dans ce cas l'écriture impute le compte d'avance du délégué au lieu de la
 * trésorerie, et son compte courant de campagne est décrémenté d'autant. Le
 * solde du délégué peut devenir créditeur : il livre alors plus que ce qu'il
 * a reçu, et la coopérative lui doit la différence jusqu'au décompte de fin
 * de campagne.</p>
 */
public class ProducerPurchaseEntity {

    @BsonId
    public UUID id;

    /** Référence affichable {@code ACP-YYYY-NNNN}. Unique par tenant. */
    public String ref;

    public LocalDate date;

    /**
     * Numéro du reçu d'achat officiel remis au producteur (carnet
     * réglementaire). C'est sa preuve de vente ; il est saisi tel quel,
     * jamais généré, et n'est pas la référence interne {@link #ref}.
     */
    public String officialReceiptRef;

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
    public UUID articleId;
    public String articleCode;
    public String articleName;
    public String articleUnit;

    // ─── Contexte ───
    public UUID siteId;
    public UUID campaignId;
    public Integer campaignYear;

    // ─── Transaction ───
    /** Camion qui a livré, tel que le carnet le note. Null : livraison à pied. */
    public String truckNumber;

    /**
     * Pesées du bordereau (CE-183). Vide pour les reçus saisis sans le
     * détail : {@link #weightKg} reste alors la seule vérité du poids.
     * Renseignées, leur somme de nets vaut {@link #weightKg}.
     */
    public java.util.List<PurchaseWeighing> weighings;

    public Integer nbSacs;
    public BigDecimal weightKg;
    public BigDecimal guaranteedPricePerKg;
    /** Montant dû au producteur : poids × prix garanti. */
    public BigDecimal amount;

    /**
     * Montant effectivement remis au producteur. Égal au montant dû sauf
     * si le paiement partiel est autorisé au niveau du tenant ; l'écart
     * devient une dette envers le producteur.
     */
    public BigDecimal amountPaid;

    public PaymentMethod paymentMethod;
    public String paymentRef;

    /** Payeur : délégué si rattaché, sinon référence choisie. */
    public UUID payerMemberId;
    public String payerName;

    /**
     * Total retenu sur cette livraison au titre des crédits et avances du
     * producteur. Ce montant ne lui est pas versé : il rembourse sa dette.
     */
    public BigDecimal creditImputed;

    /** Délégué collecteur dont le compte courant porte ce reçu (ACH-02). */
    public UUID delegateSupplierId;
    public String delegateName;

    /**
     * Rémunération du délégué constatée sur ce reçu. Elle vient s'ajouter
     * à ce que la coopérative lui doit, donc réduit d'autant sa dette.
     */
    public BigDecimal delegateMargin;

    /**
     * Mise en compte retenue au délégué sur cette livraison, en FCFA.
     *
     * <p>Figée au reçu, comme la marge et la catégorie : le taux se
     * renégocie d'une campagne à l'autre, et un état de campagne passée
     * recalculé avec le taux du jour serait faux.</p>
     */
    public BigDecimal delegateRetention;

    /**
     * Catégorie de reprise de l'apporteur, figée au reçu : le délégué s'il
     * y en a un, sinon le producteur lui-même. Figée, parce qu'un
     * fournisseur qui change de catégorie en cours de campagne ne doit pas
     * réécrire ce que ses apports passés ont coûté.
     */
    public UUID supplierCategoryId;
    public String supplierCategoryName;

    /**
     * Bordereau de livraison : les reçus qu'un délégué apporte en une fois.
     * Simple clé de regroupement attribuée à l'import, pas un objet à part,
     * pour que la matière n'ait qu'une seule origine, le reçu.
     */
    public String deliveryRef;

    /** Avance sur laquelle le reçu a été imputé, quand il y en avait une. */
    public UUID collectorAdvanceId;

    /**
     * Kilos nets déjà appelés par des bordereaux de sortie (CE-195). Le
     * disponible d'un reçu vaut son poids moins ce cumul : un reçu peut
     * partir en plusieurs chargements, le reliquat servant au suivant.
     */
    public BigDecimal dispatchedKg;

    // ─── Traces d'intégration ───
    public String movementRef;
    public String pieceRef;

    /**
     * Où en est l'écriture comptable du reçu (DEC-36, V2 de l'expert).
     * {@code POSTED} : passée, le cas de toujours ; {@code PENDING} : la
     * livraison attend le clic « Comptabiliser maintenant » du comptable
     * (mode MANUAL de la préférence tenant). Null se lit POSTED, les
     * documents d'avant le champ ayant tous leur pièce.
     */
    public String accountingStatus;
    public Instant accountingPostedAt;
    public String accountingPostedByEmail;

    /**
     * Caisse ou compte bancaire choisi à la saisie. Conservé parce que
     * l'écriture peut se passer plus tard (mode MANUAL) et doit mouvementer
     * le tiroir réellement utilisé, pas le défaut du moyen de paiement.
     */
    public UUID bankAccountId;

    // ─── Audit ───
    /**
     * ACTIVE par défaut. Les documents antérieurs à ce champ le portent
     * après la migration de reprise ; un null se lit donc comme ACTIVE.
     */
    public ProducerPurchaseStatus status = ProducerPurchaseStatus.ACTIVE;

    /** Renseigné à l'annulation, null sinon. */
    public ProducerPurchaseCancellation cancellation;

    /** POSTED sauf attente explicite : les anciens documents ont leur pièce. */
    public String accountingStatusOrPosted() {
        return accountingStatus == null ? "POSTED" : accountingStatus;
    }

    /** Le statut, en tolérant les documents d'avant le champ. */
    public ProducerPurchaseStatus statusOrActive() {
        return status == null ? ProducerPurchaseStatus.ACTIVE : status;
    }

    public boolean isCancelled() {
        return statusOrActive() == ProducerPurchaseStatus.CANCELLED;
    }

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;
    public String createdByEmail;

    /**
     * Compteur d'écritures. <strong>Ce n'est pas un verrou</strong> : aucune
     * mise à jour ne le vérifie. La concurrence est traitée autrement sur
     * cette entité (le cumul payé passe par un update conditionnel atomique). Ne pas s'y fier pour détecter une écriture
     * concurrente.
     */
    public long version = 0L;

    public ProducerPurchaseEntity() {}
}
