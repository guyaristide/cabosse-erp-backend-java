package com.ntech.cabosse.achats.entity;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Ligne d'un bon de commande. Embed dans {@link PurchaseOrderEntity}.
 *
 * <p>Snapshots des champs catalogue ({@code articleCode},
 * {@code designation}, {@code activityCodes}) pour rester lisible même si
 * l'article est renommé ou désactivé plus tard. {@code totalLineFcfa} est
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

    public BigDecimal quantity;
    /** Snapshot {@code ArticleEntity.unit}. */
    public String unit;

    public BigDecimal unitPriceFcfa;
    /** Remise en % (0–100). {@code null} = pas de remise. */
    public BigDecimal discountPct;

    /** {@code quantity × unitPriceFcfa × (1 − discountPct/100)}. Pré-calculé. */
    public BigDecimal totalLineFcfa;

    /** Snapshot {@code ArticleEntity.activityCode} (en liste pour préparer le multi-activité). */
    public List<String> activityCodes;

    public PurchaseOrderLine() {}
}
