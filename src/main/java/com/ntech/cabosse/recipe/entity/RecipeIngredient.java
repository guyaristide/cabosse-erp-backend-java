package com.ntech.cabosse.recipe.entity;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Une ligne de nomenclature : article (matière première ou emballage)
 * + quantité + unité. Sub-document de {@link RecipeEntity}.
 */
public class RecipeIngredient {

    /** FK vers {@code ArticleEntity.id} — typiquement matière première ou emballage. */
    public UUID articleId;

    /** Dénormalisé pour l'affichage rapide (libellé au moment de l'enregistrement). */
    public String articleName;

    public BigDecimal quantity;

    /** Unité utilisée dans la recette (peut différer de l'unité de l'article). */
    public String unit;

    public RecipeIngredient() {}
}
