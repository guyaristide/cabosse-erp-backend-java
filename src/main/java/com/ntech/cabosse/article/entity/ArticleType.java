package com.ntech.cabosse.article.entity;

/**
 * Catégories d'articles d'un tenant.
 *
 * <ul>
 *   <li>{@code RAW_MATERIAL} — matière première (cacao brut, latex, etc.).
 *       Entre dans la composition des recettes, sort en sortie de production.</li>
 *   <li>{@code FINISHED_PRODUCT} — produit fini (chocolat, savon, huile).
 *       Produit par les OF, vendu aux clients.</li>
 *   <li>{@code CONSUMABLE} — consommable opérationnel (produits d'entretien,
 *       carburant, fournitures de bureau). Pas dans les recettes.</li>
 *   <li>{@code PACKAGING} — emballage (sachet, bouteille, étiquette).
 *       Peut entrer dans une recette ou être consommé à la vente.</li>
 * </ul>
 *
 * <p>Sérialisé en String pour résister aux renames.</p>
 */
public enum ArticleType {
    RAW_MATERIAL,
    FINISHED_PRODUCT,
    CONSUMABLE,
    PACKAGING;
}
