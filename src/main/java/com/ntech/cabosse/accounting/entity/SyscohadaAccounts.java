package com.ntech.cabosse.accounting.entity;

import com.ntech.cabosse.article.entity.ArticleType;

/**
 * Constantes des numéros de compte SYSCOHADA utilisés par le moteur de
 * comptabilisation automatique. Les libellés vivent dans le seed du plan
 * comptable ({@code M011_CreateAccountingCollections}) — ici uniquement
 * les codes, pour ne pas dupliquer.
 *
 * <p>Référence : plan comptable SYSCOHADA révisé (acte uniforme OHADA
 * 2017, applicable Côte d'Ivoire). Les comptes choisis sont volontairement
 * minimaux pour le MVP — la granularité fine (601100 vs 601200…) sera
 * introduite si l'expert-comptable du tenant le demande.</p>
 */
public final class SyscohadaAccounts {

    private SyscohadaAccounts() {}

    // ─── Tiers ───
    public static final String FOURNISSEURS = "401000";
    public static final String CLIENTS = "411000";

    // ─── Charges (achats) ───
    /**
     * Achats de marchandises : biens achetés pour être revendus en l'état.
     * C'est bien le 601 au SYSCOHADA révisé, là où le plan français place
     * les marchandises en 607.
     */
    public static final String ACHATS_MARCHANDISES = "601000";
    /** Achats de matières premières et fournitures liées. */
    public static final String ACHATS_MATIERES = "602000";
    /** Achats stockés — autres approvisionnements (consommables). */
    public static final String ACHATS_AUTRES = "604000";
    /** Achats d'emballages. */
    public static final String ACHATS_EMBALLAGES = "608100";
    /** Transports sur achats (fret, livraisons fournisseur). */
    public static final String TRANSPORTS_SUR_ACHATS = "624000";

    // ─── Produits (ventes) ───
    /** Ventes de marchandises (revendues en l'état). */
    public static final String VENTES_MARCHANDISES = "701000";
    /** Ventes de produits finis (issus de la transformation). */
    public static final String VENTES_PRODUITS_FINIS = "702000";

    // ─── TVA ───
    /** TVA déductible sur achats. */
    public static final String TVA_DEDUCTIBLE = "445600";
    /** TVA collectée sur ventes. */
    public static final String TVA_COLLECTEE = "445700";

    // ─── Stocks et variations (inventaire) ───
    /** Stocks de marchandises (achetées pour être revendues en l'état). */
    public static final String STOCKS_MARCHANDISES = "310000";
    /** Stocks de matières premières et fournitures liées. */
    public static final String STOCKS_MATIERES = "320000";
    /** Stocks d'autres approvisionnements (consommables, emballages). */
    public static final String STOCKS_AUTRES_APPRO = "330000";
    /** Stocks de produits finis. */
    public static final String STOCKS_PRODUITS_FINIS = "360000";
    /** Variation des stocks de marchandises. */
    public static final String VARIATION_STOCKS_MARCHANDISES = "603100";
    /** Variation des stocks de matières premières. */
    public static final String VARIATION_STOCKS_MATIERES = "603200";
    /** Variation des stocks d'autres approvisionnements. */
    public static final String VARIATION_STOCKS_AUTRES = "603300";
    /** Variation des stocks de produits fabriqués (compte de produits). */
    public static final String VARIATION_STOCKS_PRODUITS = "736000";

    /** Associés, opérations sur le capital — souscription des parts sociales (réf. v7). */
    public static final String ASSOCIES_CAPITAL = "461000";

    /** Fournisseurs, avances et acomptes versés — avances aux délégués (ACH-02). */
    public static final String FOURNISSEURS_AVANCES = "409100";

    // ─── Fin d'exercice (backlog CPT-12) ───
    /** Résultat net de l'exercice. */
    public static final String RESULTAT_EXERCICE = "130000";
    /** Produits en cours (constat des en-cours à l'arrêté). */
    public static final String EN_COURS = "340000";
    /** Variation des en-cours. */
    public static final String VARIATION_EN_COURS = "734000";
    /** Impôt sur le résultat. */
    public static final String IMPOT_RESULTAT = "891000";
    /** État, impôt sur les bénéfices. */
    public static final String ETAT_IMPOT_BENEFICES = "441000";

    // ─── Divers ───
    /** Créditeurs et débiteurs divers — écritures d'attente du rapprochement. */
    public static final String COMPTES_ATTENTE = "471000";
    /** Frais bancaires et assimilés. */
    public static final String FRAIS_BANCAIRES = "631000";

    // ─── Trésorerie (par défaut) ───
    /** Banque — compte courant par défaut si aucun BankAccount précisé. */
    public static final String BANQUE_DEFAULT = "521000";
    /** Caisse — espèces par défaut (57x AUDCIF, réf. jeux d'écritures v7). */
    public static final String CAISSE_DEFAULT = "571000";
    /**
     * Virements de fonds : compte de passage entre deux comptes de
     * trésorerie. Sans lui, un retrait parti le matin et reçu le soir
     * ferait disparaître la somme d'un jour à l'autre.
     */
    public static final String VIREMENTS_INTERNES = "585000";

    /**
     * Résout le compte de charge à débiter pour une ligne d'achat selon
     * la nature de l'article. {@code TRANSPORT} retourne 624 ; les autres
     * types tombent sur 601 (matières), 604 (consommables), 6081
     * (emballages) ou 601 pour les produits finis re-achetés (cas rare).
     */
    /**
     * Compte de stock (classe 3) mouvementé par une régularisation
     * d'inventaire selon la nature de l'article. {@code TRANSPORT} n'est
     * jamais stocké : retour {@code null}, la ligne est ignorée.
     */
    public static String stockAccountFor(ArticleType type) {
        if (type == null) return STOCKS_MARCHANDISES;
        return switch (type) {
            case TRANSPORT -> null;
            case CONSUMABLE, PACKAGING -> STOCKS_AUTRES_APPRO;
            case FINISHED_PRODUCT -> STOCKS_PRODUITS_FINIS;
            case MERCHANDISE -> STOCKS_MARCHANDISES;
            case RAW_MATERIAL -> STOCKS_MATIERES;
        };
    }

    /** Contrepartie de variation de stock associée à {@link #stockAccountFor}. */
    public static String stockVariationAccountFor(ArticleType type) {
        if (type == null) return VARIATION_STOCKS_MARCHANDISES;
        return switch (type) {
            case TRANSPORT -> null;
            case CONSUMABLE, PACKAGING -> VARIATION_STOCKS_AUTRES;
            case FINISHED_PRODUCT -> VARIATION_STOCKS_PRODUITS;
            case MERCHANDISE -> VARIATION_STOCKS_MARCHANDISES;
            case RAW_MATERIAL -> VARIATION_STOCKS_MATIERES;
        };
    }

    public static String purchaseChargeAccountFor(ArticleType type) {
        if (type == null) return ACHATS_MATIERES;
        return switch (type) {
            case TRANSPORT -> TRANSPORTS_SUR_ACHATS;
            case CONSUMABLE -> ACHATS_AUTRES;
            case PACKAGING -> ACHATS_EMBALLAGES;
            case RAW_MATERIAL -> ACHATS_MATIERES;
            // Un produit fini acheté ne l'est que pour être revendu tel
            // quel : c'est une marchandise, quel que soit le fait qu'on
            // sache aussi le fabriquer.
            case MERCHANDISE, FINISHED_PRODUCT -> ACHATS_MARCHANDISES;
        };
    }

    /**
     * Compte de produit par défaut d'une vente, selon la nature de
     * l'article vendu. La fiche article peut le surcharger.
     */
    public static String saleRevenueAccountFor(ArticleType type) {
        if (type == null) return VENTES_PRODUITS_FINIS;
        return switch (type) {
            case MERCHANDISE -> VENTES_MARCHANDISES;
            default -> VENTES_PRODUITS_FINIS;
        };
    }
}
