package com.ntech.cabosse.accounting.entity;

/**
 * Famille comptable d'un compte SYSCOHADA — sert au regroupement dans la
 * page comptabilité et au calcul des soldes (sens débiteur/créditeur).
 *
 * <p>Dérivée du préfixe du numéro de compte (classe 1 à 8 SYSCOHADA) lors
 * du seed initial. Stockée pour éviter d'avoir à reparser le numéro à
 * chaque lecture.</p>
 */
public enum AccountFamily {
    /** Classe 6 — charges (601, 604, 624…). Sens naturel : débiteur. */
    CHARGES,
    /** Classe 7 — produits (701, 706…). Sens naturel : créditeur. */
    PRODUITS,
    /** Classe 401 — fournisseurs. Sens naturel : créditeur. */
    FOURNISSEURS,
    /** Classe 411 — clients. Sens naturel : débiteur. */
    CLIENTS,
    /** Classe 521/530 — banque, caisse. Sens naturel : débiteur. */
    TRESORERIE,
    /** Classe 4456/4457 — TVA déductible/collectée. */
    TVA,
    /** Classe 2 — immobilisations (non utilisé au MVP). */
    IMMOBILISATIONS,
    /** Autres comptes (capital, stocks valorisés, ajustements). */
    AUTRES
}
