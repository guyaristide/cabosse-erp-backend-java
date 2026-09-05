package com.ntech.cabosse.sale.entity;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Une ligne de vente. Snapshots article + prix au moment de la vente.
 * Le {@code cmupAtSale} est figé à la création — sert au calcul de
 * la marge brute et à la valorisation du mouvement OUT au moment du
 * "marquage livré".
 */
public class SaleLine {

    public UUID id;

    // ─── Article (snapshots) ───
    public UUID articleId;
    public String articleCode;
    public String articleName;
    public String articleUnit;

    public BigDecimal quantity;
    /** PU négocié sur la ligne. */
    public BigDecimal unitPrice;
    /** PU catalogue snapshoté (Article.standardSalePrice). Pour reporting écart. */
    public BigDecimal standardPrice;
    /** Remise ligne (0..100). */
    public BigDecimal discountPct;
    /** {@code qty × pu × (1 - discount/100)}. */
    public BigDecimal lineTotalHt;

    /** CMUP courant snapshoté à la création de la vente. */
    public BigDecimal cmupAtSale;
    /** {@code lineTotalHt - qty × cmup}. Info. */
    public BigDecimal lineMargin;

    /** Lot tracé optionnellement sur le mouvement OUT au marquage livré. */
    public String lotRef;

    public SaleLine() {}
}
