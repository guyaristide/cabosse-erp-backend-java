package com.ntech.cabosse.recipe.entity;

/**
 * Une étape de production d'une recette. Sub-document de
 * {@link RecipeEntity}, optionnel : une recette peut n'avoir aucune
 * étape (l'OF fonctionnera alors en mono-statut DRAFT → IN_PROGRESS →
 * COMPLETED).
 *
 * <p>L'ordre est assigné par le service à partir de la position dans
 * la liste — l'utilisateur ne renseigne pas {@code order}. Cela évite
 * les conflits sur les renumérotations à l'édition (drag-and-drop).</p>
 */
public class RecipeStep {

    /** Position dans la séquence — 0-based, assigné par le service. */
    public int order;

    /** Libellé libre de l'étape ({@code "Torréfaction"}, {@code "Saponification"}…). */
    public String name;

    /** Description / consigne opératoire optionnelle. */
    public String description;

    /** Durée attendue indicative, en minutes. Optionnel. */
    public Integer expectedDurationMinutes;

    public RecipeStep() {}
}
