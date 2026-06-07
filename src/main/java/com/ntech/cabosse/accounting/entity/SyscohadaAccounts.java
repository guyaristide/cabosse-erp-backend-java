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

    // ─── Trésorerie (par défaut) ───
    /** Banque — compte courant par défaut si aucun BankAccount précisé. */
    public static final String BANQUE_DEFAULT = "521";
    /** Caisse — espèces par défaut. */
    public static final String CAISSE_DEFAULT = "530";

    /**
     * Résout le compte de charge à débiter pour une ligne d'achat selon
     * la nature de l'article. {@code TRANSPORT} retourne 624 ; les autres
     * types tombent sur 601 (matières), 604 (consommables), 6081
     * (emballages) ou 601 pour les produits finis re-achetés (cas rare).
     */
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
