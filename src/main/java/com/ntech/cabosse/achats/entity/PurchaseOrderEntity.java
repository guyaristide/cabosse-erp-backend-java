package com.ntech.cabosse.achats.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Bon de commande fournisseur (M2 Achats). Tenant-scoped (collection
 * {@code purchase_orders} dans la base du tenant).
 *
 * <p>POJO Mongo non Panache — même pattern que {@code ArticleEntity}.
 * Le tenant context fournit la base via {@code TenantMongoDatabaseProvider}.</p>
 *
 * <p>{@link #ref} est généré côté serveur, format {@code BC-YYYY-NNNN}
 * (compteur séquentiel par tenant et par année, voir
 * {@code PurchaseOrderRefService}).</p>
 */
public class PurchaseOrderEntity {

    @BsonId
    public UUID id;

    /** Référence affichable {@code BC-2026-0001}. Unique par tenant. */
    public String ref;

    /** Site de réception. Initialisé sur le site actif lors de la création. */
    public UUID siteId;

    // ─── Fournisseur (FK + snapshot) ───
    public UUID supplierId;
    public String supplierName;
    public String supplierLegalName;

    // ─── Dates ───
    public LocalDate orderDate;
    public LocalDate deliveryDate;
    public LocalDate invoiceDate;
    public String invoiceNumber;

    /** Conditions de paiement (texte libre, snapshot du fournisseur ou override). */
    public String paymentTerms;

    // ─── Lignes ───
    public List<PurchaseOrderLine> lines;

    // ─── Totaux ───
    public BigDecimal subtotalHt;
    /**
     * Frais de transport totaux du BC. Calculé automatiquement comme la
     * somme des lignes {@code articleType=TRANSPORT} si présentes ; sinon
     * respecte la valeur fournie par le payload (compat legacy).
     */
    public BigDecimal transport;
    /** Taux TVA en % (0–100). */
    public BigDecimal vatRatePct;
    public BigDecimal vat;
    public BigDecimal totalTtc;

    /**
     * Activités du tenant touchées par ce BC. Dérivé des lignes —
     * recalculé à chaque save.
     */
    public List<String> activityCodes;

    /** Référence vers {@code CloudFileEntity.id} pour la facture jointe. */
    public UUID attachmentFileId;

    public String notes;

    /**
     * Si {@code true} au moment du {@code markDelivered}, les frais
     * transport sont ventilés au prorata HT sur les lignes matière /
     * conso / packaging et incorporés à leur {@code unitCost} d'entrée
     * stock (ajustant le CMUP). Sinon le transport reste une dépense
     * dissociée du coût matière. Gelé après DELIVERED.
     */
    public boolean incorporateFreightInCmup = false;

    /**
     * Surcharge ponctuelle de la préférence tenant {@code vatRecoverable}.
     *
     * <p>{@code null} (défaut) : on suit la préférence tenant courante au
     * moment du markDelivered. {@code true} : ce BC bénéficie d'une TVA
     * récupérable (CMUP = HT) même si le tenant en règle générale ne la
     * récupère pas. {@code false} : la TVA de ce BC est non récupérable
     * (CMUP = TTC) même si le tenant la récupère habituellement.</p>
     *
     * <p>Stocké en {@code Boolean} (nullable) précisément pour distinguer
     * "non précisé / hériter du tenant" de "explicitement à false".</p>
     */
    public Boolean vatRecoverableOverride;

    public BcStatus status = BcStatus.DRAFT;
    public PurchaseOrderCancellation cancellation;

    /** Demande d'achat dont ce BC est issu (backlog ACH-01). {@code null} = saisie directe. */
    public UUID purchaseRequestId;
    public String purchaseRequestRef;

    /**
     * Campagne de rattachement, déduite de {@link #orderDate}. Nulle quand aucune
     * campagne ne couvre la date et qu'aucune n'est ouverte.
     */
    public UUID campaignId;

    /** Année de la campagne, dénormalisée pour les regroupements. */
    public Integer campaignYear;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;
    /** Email de l'acteur ayant créé le BC (denorm pour affichage). */
    public String createdByEmail;

    /** Lock optimiste — incrémenté à chaque écriture (cf. PurchaseOrderRepository.replace). */
    public long version = 0L;

    public PurchaseOrderEntity() {}
}
