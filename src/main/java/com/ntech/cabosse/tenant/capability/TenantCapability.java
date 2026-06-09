package com.ntech.cabosse.tenant.capability;

/**
 * Capacités fonctionnelles activables sur un tenant. Chaque capacité
 * pilote l'apparition d'un sous-ensemble de modules ERP, indépendamment
 * de la filière du tenant.
 *
 * <p>Les capacités sont dérivées de deux sources orthogonales :
 * <ul>
 *   <li>{@link com.ntech.cabosse.tenant.entity.TenantOrganizationModel}
 *       — structure juridique. {@code COOPERATIVE} active
 *       automatiquement {@link #HAS_MEMBERS} et {@link #HAS_SUSTAINABILITY}.</li>
 *   <li>{@code tenant.activities[].code} — filières / activités
 *       économiques. Chaque {@code IndustryEntity} porte la liste des
 *       capacités qu'elle active (champ {@code activates}).</li>
 * </ul>
 *
 * <p>Le service de dérivation est {@code TenantCapabilityService.capabilitiesOf()}.</p>
 */
public enum TenantCapability {

    /**
     * Registre des membres-producteurs, livraisons par campagne,
     * rémunération avec primes/ristournes, épargne et crédit coopératifs.
     * Activé pour tout tenant {@code COOPERATIVE} ou {@code INFORMAL_GROUP}.
     */
    HAS_MEMBERS,

    /**
     * Parcelles géolocalisées (polygones GPS), calendrier cultural,
     * agroforesterie. Activé pour toute filière de production agricole
     * avec terrain (cacao production, hévéa, palmier, coton, fruits).
     */
    HAS_PARCELS,

    /**
     * Bacs de fermentation, profils température, durée optimale.
     * Activé pour les filières où la fermentation est un étape qualifiante
     * (cacao production, café specialty, vanille).
     */
    HAS_FERMENTATION,

    /**
     * Séchage solaire / artificiel avec suivi humidité. Activé pour les
     * filières où le séchage est une étape de transformation primaire
     * (cacao, café, manioc, fruits, noix-cajou, riz, maïs).
     */
    HAS_DRYING,

    /**
     * Profils de torréfaction avec température / durée / perte de masse.
     * Activé pour les filières chocolaterie et café.
     */
    HAS_ROASTING,

    /**
     * Conformité EUDR (UE 2023/1115) : géolocalisation parcelles,
     * surveillance déforestation post-31/12/2020, déclaration de
     * diligence raisonnée (DDR) par lot exporté. Activé pour les
     * filières soumises au règlement : cacao, café, hévéa, palmier à
     * huile, soja, bois, bovin.
     */
    HAS_EUDR_COMPLIANCE,

    /**
     * Rapport de durabilité annuel, certifications, suivi LIRP (Living
     * Income Reference Price), CLMRS (travail enfants), bilan carbone,
     * indicateurs environnementaux. Activé pour {@code COOPERATIVE} par
     * défaut ; peut aussi être activé par une certification déclarée
     * (Fairtrade, Rainforest Alliance, Bio).
     */
    HAS_SUSTAINABILITY
}
