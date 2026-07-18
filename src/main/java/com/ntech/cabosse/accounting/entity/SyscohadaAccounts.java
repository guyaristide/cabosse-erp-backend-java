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
    public static final String FOURNISSEURS = "401";
    public static final String CLIENTS = "411";

    // ─── Charges (achats) ───
    /** Achats de matières premières. */
    public static final String ACHATS_MATIERES = "601";
    /** Achats stockés — autres approvisionnements (consommables). */
    public static final String ACHATS_AUTRES = "604";
    /** Achats d'emballages. */
    public static final String ACHATS_EMBALLAGES = "6081";
    /** Transports sur achats (fret, livraisons fournisseur). */
    public static final String TRANSPORTS_SUR_ACHATS = "624";

    // ─── Produits (ventes) ───
    /** Ventes de marchandises / produits finis (MVP : compte unique 701). */
    public static final String VENTES_PRODUITS_FINIS = "701";

    // ─── TVA ───
    /** TVA déductible sur achats. */
    public static final String TVA_DEDUCTIBLE = "4456";
    /** TVA collectée sur ventes. */
    public static final String TVA_COLLECTEE = "4457";

    // ─── Stocks et variations (inventaire) ───
    /** Stocks de marchandises (produits agricoles achetés-revendus, réf. jeux d'écritures v7). */
    public static final String STOCKS_MARCHANDISES = "31";
    /** Stocks d'autres approvisionnements (consommables, emballages). */
    public static final String STOCKS_AUTRES_APPRO = "33";
    /** Stocks de produits finis. */
    public static final String STOCKS_PRODUITS_FINIS = "36";
    /** Variation des stocks de marchandises. */
    public static final String VARIATION_STOCKS_MARCHANDISES = "6031";
    /** Variation des stocks d'autres approvisionnements. */
    public static final String VARIATION_STOCKS_AUTRES = "6033";
    /** Variation des stocks de produits fabriqués (compte de produits). */
    public static final String VARIATION_STOCKS_PRODUITS = "736";

    /** Associés, opérations sur le capital — souscription des parts sociales (réf. v7). */
    public static final String ASSOCIES_CAPITAL = "461";

    // ─── Fin d'exercice (backlog CPT-12) ───
    /** Résultat net de l'exercice. */
    public static final String RESULTAT_EXERCICE = "13";
    /** Produits en cours (constat des en-cours à l'arrêté). */
    public static final String EN_COURS = "34";
    /** Variation des en-cours. */
    public static final String VARIATION_EN_COURS = "734";
    /** Impôt sur le résultat. */
    public static final String IMPOT_RESULTAT = "891";
    /** État, impôt sur les bénéfices. */
    public static final String ETAT_IMPOT_BENEFICES = "441";

    // ─── Divers ───
    /** Créditeurs et débiteurs divers — écritures d'attente du rapprochement. */
    public static final String COMPTES_ATTENTE = "471";
    /** Frais bancaires et assimilés. */
    public static final String FRAIS_BANCAIRES = "631";

    // ─── Trésorerie (par défaut) ───
    /** Banque — compte courant par défaut si aucun BankAccount précisé. */
    public static final String BANQUE_DEFAULT = "521";
    /** Caisse — espèces par défaut (57x AUDCIF, réf. jeux d'écritures v7). */
    public static final String CAISSE_DEFAULT = "571";

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
            case RAW_MATERIAL -> STOCKS_MARCHANDISES;
        };
    }

    /** Contrepartie de variation de stock associée à {@link #stockAccountFor}. */
    public static String stockVariationAccountFor(ArticleType type) {
        if (type == null) return VARIATION_STOCKS_MARCHANDISES;
        return switch (type) {
            case TRANSPORT -> null;
            case CONSUMABLE, PACKAGING -> VARIATION_STOCKS_AUTRES;
            case FINISHED_PRODUCT -> VARIATION_STOCKS_PRODUITS;
            case RAW_MATERIAL -> VARIATION_STOCKS_MARCHANDISES;
        };
    }

    public static String purchaseChargeAccountFor(ArticleType type) {
        if (type == null) return ACHATS_MATIERES;
        return switch (type) {
            case TRANSPORT -> TRANSPORTS_SUR_ACHATS;
            case CONSUMABLE -> ACHATS_AUTRES;
            case PACKAGING -> ACHATS_EMBALLAGES;
            case RAW_MATERIAL, FINISHED_PRODUCT -> ACHATS_MATIERES;
        };
    }
}
