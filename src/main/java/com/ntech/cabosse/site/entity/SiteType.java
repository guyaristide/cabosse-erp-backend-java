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
 *   <li>{@code SECTION_WAREHOUSE} — magasin de stockage d'une section.
 *       Première étape du circuit de collecte : la matière achetée aux
 *       producteurs y entre avant d'être acheminée. Plusieurs par
 *       structure, un par zone de collecte en général.</li>
 *   <li>{@code CENTRAL_WAREHOUSE} — magasin central. Regroupe ce que les
 *       magasins de section ont collecté, avant transport vers le client
 *       ou vers la transformation. C'est là que se constituent les lots
 *       expédiés.</li>
 * </ul>
 *
 * <p>Le type ne conditionne aucun traitement : il nomme le rôle du site
 * dans la chaîne, il ne restreint ni les mouvements de stock ni les
 * opérations. Un magasin qui se met à transformer n'a pas à changer de
 * type pour que le système l'accepte.</p>
 *
 * <p>Le quota du plan est appliqué globalement (tous types confondus) au
 * MVP — voir {@code SiteService.assertWithinQuota}. Si on veut plus tard
 * différencier transformation et vente côté commercial, ajouter
 * {@code maxTransformationSites} et {@code maxSalesPoints} sur le plan.</p>
 */
public enum SiteType {
    TRANSFORMATION,
    SALES_POINT,
    SECTION_WAREHOUSE,
    CENTRAL_WAREHOUSE;
}
