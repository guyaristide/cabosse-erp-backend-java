package com.ntech.cabosse.recipe.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Recette / nomenclature (Bill of Materials). Tenant-scoped.
 *
 * <p>Une recette produit <strong>un</strong> produit fini en consommant
 * N ingrédients (matières premières + emballages). Utilisée par les
 * ordres de fabrication (M3) pour calculer les sorties matières et
 * dériver le coût standard.</p>
 */
public class RecipeEntity {

    @BsonId
    public UUID id;

    public String code;
    public String name;
    public String description;

    /** FK vers {@code ArticleEntity.id} de type {@code FINISHED_PRODUCT}. */
    public UUID finishedProductId;

    /** Dénormalisé pour l'affichage rapide. */
    public String finishedProductName;

    /** Quantité de produit fini produit par une exécution de la recette. */
    public BigDecimal yieldQty;

    /** Unité du rendement ({@code kg}, {@code pcs}, …). */
    public String yieldUnit;

    /** Lignes de nomenclature (matières + emballages). */
    public List<RecipeIngredient> ingredients = new ArrayList<>();

    /**
     * Étapes de production ordonnées. Optionnel — recette sans étapes →
     * l'OF s'exécute en mono-statut. Avec étapes → l'OF avance étape
     * par étape (cf. {@code ManufacturingOrderService.advanceStep}).
     * Les étapes sont snapshotées sur l'OF à la création pour garantir
     * que l'OF reste lisible si la recette évolue.
     */
    public List<RecipeStep> steps = new ArrayList<>();

    public boolean active = true;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;
}
