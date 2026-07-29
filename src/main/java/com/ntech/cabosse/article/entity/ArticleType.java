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
 *   <li>{@code MERCHANDISE} — marchandise : achetée pour être revendue
 *       <strong>en l'état</strong>, sans transformation. La distinction
 *       avec la matière première n'est pas cosmétique : elle décide du
 *       compte d'achat (601 contre 602) et du compte de vente (701 contre
 *       702), et une comptabilité qui les confond est reprise par le
 *       cabinet. Une même structure peut acheter le même produit sous les
 *       deux natures ; le passage de l'une à l'autre se fait par une
 *       requalification de stock, jamais en changeant le type de
 *       l'article.</li>
 *   <li>{@code TRANSPORT} — prestation logistique (livraison, fret,
 *       affrètement). Pas de stock, pas de CMUP propre. Apparaît comme
 *       ligne dans les BC ; selon le toggle {@code incorporateFreightInCmup}
 *       du BC, son montant est ventilé au prorata HT sur les autres
 *       lignes pour ajuster leur CMUP d'entrée stock.</li>
 * </ul>
 *
 * <p>Sérialisé en String pour résister aux renames.</p>
 */
public enum ArticleType {
    RAW_MATERIAL,
    MERCHANDISE,
    FINISHED_PRODUCT,
    CONSUMABLE,
    PACKAGING,
    TRANSPORT;
}
