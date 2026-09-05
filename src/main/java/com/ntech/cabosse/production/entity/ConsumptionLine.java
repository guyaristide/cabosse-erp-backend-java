package com.ntech.cabosse.production.entity;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Une ligne de consommation matière sur un ordre de fabrication.
 * Calculée à la création depuis {@code recipe.ingredients × ratio}, où
 * {@code ratio = of.plannedQty / recipe.yieldQty}.
 *
 * <p>Les valeurs {@code cmupAtConsumption} et {@code totalCost}
 * sont figées au démarrage de l'OF (transition DRAFT → IN_PROGRESS).
 * Elles servent à la fois à la contre-passation (re-créditer au PU
 * initial) et au calcul du CMUP du produit fini à la complétion.</p>
 */
public class ConsumptionLine {

    /** UUID stable de la ligne — sert à la traçabilité des mouvements liés. */
    public UUID id;

    public UUID articleId;
    public String articleCode;
    public String articleName;
    public String articleUnit;

    /** Quantité calculée depuis la recette. */
    public BigDecimal plannedQty;

    /** Quantité effectivement consommée au démarrage. Pas d'ajustement v1. */
    public BigDecimal consumedQty;

    /** CMUP courant snapshoté à l'instant de la consommation. */
    public BigDecimal cmupAtConsumption;

    /** {@code consumedQty × cmupAtConsumption}. */
    public BigDecimal totalCost;

    public ConsumptionLine() {}
}
