package com.ntech.cabosse.tenant.entity;

/**
 * Forme juridique du tenant. Sérialisée en BSON sous forme de {@code name()}
 * (string). L'UI mappe vers un libellé localisé côté frontend.
 */
public enum LegalForm {

    /** Société à responsabilité limitée. */
    SARL,

    /** Société anonyme. */
    SA,

    /** Coopérative agricole. */
    COOPERATIVE,

    /** Groupement d'intérêt économique. */
    GIE,

    /** Entreprise individuelle. */
    INDIVIDUAL,

    /** Autre forme non couverte ci-dessus. */
    AUTRE
}
