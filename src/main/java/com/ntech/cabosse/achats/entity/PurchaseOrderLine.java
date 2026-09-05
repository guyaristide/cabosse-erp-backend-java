package com.ntech.cabosse.achats.entity;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Ligne d'un bon de commande. Embed dans {@link PurchaseOrderEntity}.
 *
 * <p>Snapshots des champs catalogue ({@code articleCode},
 * {@code designation}, {@code activityCodes}) pour rester lisible même si
 * l'article est renommé ou désactivé plus tard. {@code totalLine} est
 * pré-calculé et persisté pour faciliter l'audit et les exports.</p>
 */
public class PurchaseOrderLine {

    /** Identifiant stable de la ligne dans le BC. */
    public UUID id;

    public UUID articleId;
    /** Snapshot {@code ArticleEntity.code}. */
    public String articleCode;
    /** Snapshot {@code ArticleEntity.name}. */
    public String designation;
    /**
     * Snapshot {@code ArticleEntity.type} (nom de l'enum). Permet de
     * distinguer les lignes TRANSPORT (pas d'entrée stock, traitement
     * spécial pour la ventilation au CMUP) sans re-fetch de l'article.
     * Nullable pour les BC créés avant l'introduction du champ.
     */
    public String articleType;

    public BigDecimal quantity;
    /** Snapshot {@code ArticleEntity.unit}. */
    public String unit;

    public BigDecimal unitPrice;
    /** Remise en % (0–100). {@code null} = pas de remise. */
    public BigDecimal discountPct;

    /** {@code quantity × unitPrice × (1 − discountPct/100)}. Pré-calculé. */
    public BigDecimal totalLine;

    /** Snapshot {@code ArticleEntity.activityCode} (en liste pour préparer le multi-activité). */
    public List<String> activityCodes;

    public PurchaseOrderLine() {}
}
