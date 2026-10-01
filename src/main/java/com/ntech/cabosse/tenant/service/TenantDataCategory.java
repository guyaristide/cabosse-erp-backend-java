package com.ntech.cabosse.tenant.service;

/**
 * Les familles de données d'une structure, du point de vue d'une remise à
 * plat.
 *
 * <p>Une remise à plat ne sert pas qu'à repartir de zéro. Après une saison
 * d'essai, ce qu'on veut jeter, ce sont les écritures et les livraisons,
 * pas le registre de quatre mille producteurs ni le plan comptable qu'on a
 * mis un mois à ajuster (demandé le 30/09/2026).</p>
 *
 * <p>Les trois premières familles se conservent séparément. La quatrième
 * part toujours : c'est la raison même de l'opération.</p>
 */
public enum TenantDataCategory {

    /**
     * Ce qui décrit le fonctionnement de la structure : sites, exercices,
     * périodes comptables, campagnes et leurs objectifs, règles de
     * notification.
     */
    SETTINGS,

    /**
     * Les nomenclatures : plan comptable, articles, unités, variétés,
     * localités et leur découpage, types de dépense et de pièce, centres
     * de coût, programmes, clés de répartition, recettes, normes et grades
     * de qualité, comptes bancaires, opérateurs.
     */
    NOMENCLATURES,

    /**
     * Le registre des tiers : producteurs et leurs parcelles,
     * fournisseurs, clients, et la position tenue par un délégué.
     */
    PARTIES,

    /**
     * Tout ce qui se produit : achats et reçus, livraisons, ventes,
     * stocks et mouvements, production, écritures et pièces, règlements,
     * avances, notifications émises, journaux. Toujours effacé.
     */
    OPERATIONS
}
