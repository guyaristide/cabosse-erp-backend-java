package com.ntech.cabosse.site.entity;

/**
 * Type de site dans la chaîne logistique du tenant.
 *
 * <ul>
 *   <li>{@code TRANSFORMATION} — usine, atelier de transformation. C'est
 *       le cœur métier (production, stock de matière première et de
 *       produit fini, traçabilité). Compté dans le quota {@code maxSites}
 *       du plan tarifaire.</li>
 *   <li>{@code SALES_POINT} — point de vente, boutique, dépôt commercial.
 *       Reçoit du stock de produit fini transféré depuis un site de
 *       transformation. Aucun lien dur (un point de vente appartient au
 *       tenant, pas à un site précis ; le rattachement se fait
 *       dynamiquement via les transferts de stock).</li>
 * </ul>
 *
 * <p>Le quota du plan est appliqué globalement (tous types confondus) au
 * MVP — voir {@code SiteService.assertWithinQuota}. Si on veut plus tard
 * différencier transformation et vente côté commercial, ajouter
 * {@code maxTransformationSites} et {@code maxSalesPoints} sur le plan.</p>
 */
public enum SiteType {
    TRANSFORMATION,
    SALES_POINT;
}
