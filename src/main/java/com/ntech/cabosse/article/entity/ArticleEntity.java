package com.ntech.cabosse.article.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Article du catalogue tenant (matière, produit fini, consommable,
 * emballage). Vit dans la base du tenant (collection {@code articles}).
 *
 * <p>POJO Mongo (pas Panache) — même rationale que {@code SiteEntity}.</p>
 */
public class ArticleEntity {

    @BsonId
    public UUID id;

    /** {@link ArticleType} sérialisé en String. */
    public String type;

    /** Slug stable, FK depuis stocks, mouvements, recettes, BC, ventes. */
    public String code;

    public String name;
    public String description;

    /** Unité de mesure : {@code kg}, {@code g}, {@code L}, {@code mL}, {@code pcs}, {@code sac}… */
    public String unit;

    /** Coût standard unitaire (FCFA). Optionnel — utilisé en attente du CMUP. */
    public BigDecimal standardCost;

    /** Prix de vente standard (FCFA). Optionnel — n'a de sens que sur PF. */
    public BigDecimal standardSalePrice;

    /**
     * Code activité tenant ({@code TenantActivity.code}) auquel cet article
     * est rattaché. Permet le calcul de marge par activité. Optionnel.
     */
    public String activityCode;

    /**
     * Est-ce que l'article est géré en stock ? {@code true} par défaut.
     * Mettre à {@code false} pour les charges/services qui n'ont pas
     * d'inventaire (ex : prestation transport, frais bancaires saisis
     * comme "article" dans certains workflows). Masque côté UI les
     * notions de coût, seuil et PMP.
     */
    public boolean stockable = true;

    /**
     * Seuil d'alerte stock (dans l'unité de l'article). Une alerte
     * déclenche dans les écrans Stocks dès que la quantité physique
     * passe sous ce seuil. {@code null} = pas d'alerte.
     */
    public BigDecimal alertThreshold;

    /** Code-barres EAN/UPC. Optionnel — utilisé pour scan caisse/réception. */
    public String barcode;

    /**
     * Taux de TVA applicable (en %). {@code null} si non assujetti ou
     * non pertinent. Repris par défaut dans les devis / factures.
     */
    public BigDecimal vatRate;

    /**
     * Compte de charge SYSCOHADA débité pour les achats de cet article
     * (backlog CPT-11, réf. jeux d'écritures v7 : 6011 cacao marchand,
     * 6012 certifié, 6021 engrais…). {@code null} = résolution par
     * défaut selon le type d'article (601/604/6081/624).
     */
    public String purchaseChargeAccount;

    /**
     * Poids unitaire en grammes. Pertinent uniquement pour les
     * {@link ArticleType#FINISHED_PRODUCT} — sert au calcul du poids
     * total produit et du rendement d'un OF
     * ({@code totalWeightKg = producedQty * unitWeightGrams / 1000}).
     * {@code null} si non renseigné ou non pertinent (matière, conso).
     */
    public Integer unitWeightGrams;

    public boolean active = true;

    /**
     * Référence vers {@code CloudFileEntity.id} pour l'image de l'article.
     * Le fichier vit dans {@code <tenant_db>.cloud_files} (scope
     * {@link com.ntech.cabosse.shared.storage.CloudFileScope#TENANT}).
     * Pas de blob inline ici. Les méta (mimeType, sizeBytes) ne sont
     * <strong>pas</strong> dupliquées — on les lit depuis
     * {@code CloudFileEntity} au moment du service.
     */
    public UUID imageFileId;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;
}
