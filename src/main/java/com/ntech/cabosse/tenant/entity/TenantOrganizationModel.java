package com.ntech.cabosse.tenant.entity;

/**
 * Structure juridique / organisationnelle du tenant. Dimension orthogonale
 * à la filière ({@link TenantActivity}) : une coopérative peut produire du
 * cacao, du café, du karité, du manioc, etc. — sa qualité de "coopérative"
 * tient à sa structure, pas à la matière qu'elle traite.
 *
 * <p>Une seule valeur par tenant. Pilote l'activation de certaines
 * capacités fonctionnelles (notamment {@code hasMembers} et
 * {@code hasSustainability}) indépendamment de la filière.</p>
 */
public enum TenantOrganizationModel {

    /**
     * Coopérative / société coopérative / mutuelle. Caractérisé par un
     * registre de membres-producteurs, une rémunération par campagne,
     * potentiellement épargne et crédit coopératifs.
     */
    COOPERATIVE,

    /**
     * Entreprise privée (SARL, SA, EI). Pas de notion de membres : les
     * fournisseurs sont des entités commerciales classiques.
     */
    PRIVATE_COMPANY,

    /**
     * Groupement informel / GIE / association de producteurs sans
     * personnalité morale de coopérative. Le pack membres est utile mais
     * la rémunération est plus souple (pas de ristournes formelles).
     */
    INFORMAL_GROUP,

    /**
     * Entité publique / ONG / projet de développement. Structure proche
     * de l'entreprise privée pour le SI mais avec des obligations de
     * reporting bailleurs additionnelles.
     */
    PUBLIC_ENTITY
}
