package com.ntech.cabosse.accounting.entity;

/**
 * Classe SYSCOHADA révisé d'un compte, déduite du premier chiffre de son
 * numéro. Sert au regroupement dans la page comptabilité, au filtrage et
 * à la colonne « famille » des exports.
 *
 * <p>Les valeurs étaient auparavant un regroupement fonctionnel maison
 * (achats, ventes, tiers, trésorerie…) qui ne correspondait pas au
 * référentiel. Un compte de capital ou un compte de stock s'affichait en
 * « Charges », et un compte de la classe 7 en « Ventes » alors que la
 * classe couvre tous les produits des activités ordinaires. Sur un plan
 * comptable, un intitulé approximatif n'est pas une nuance de vocabulaire :
 * c'est une classification fausse, que l'expert-comptable lit comme
 * telle.</p>
 *
 * <p>La classe se <strong>déduit</strong> désormais du numéro et ne se
 * saisit plus. Le premier chiffre la détermine entièrement, et rien ne
 * justifie de laisser diverger une donnée qui se calcule.</p>
 */
public enum AccountFamily {

    /** Classe 1 — comptes de ressources durables. */
    RESSOURCES_DURABLES('1'),

    /** Classe 2 — comptes d'actif immobilisé. */
    ACTIF_IMMOBILISE('2'),

    /** Classe 3 — comptes de stocks. */
    STOCKS('3'),

    /** Classe 4 — comptes de tiers. */
    TIERS('4'),

    /** Classe 5 — comptes de trésorerie. */
    TRESORERIE('5'),

    /** Classe 6 — comptes de charges des activités ordinaires. */
    CHARGES_ORDINAIRES('6'),

    /** Classe 7 — comptes de produits des activités ordinaires. */
    PRODUITS_ORDINAIRES('7'),

    /** Classe 8 — comptes des autres charges et des autres produits (HAO). */
    AUTRES_CHARGES_ET_PRODUITS('8'),

    /**
     * Classe 9 — comptes des engagements hors bilan et comptabilité
     * analytique de gestion.
     */
    ENGAGEMENTS_ET_ANALYTIQUE('9');

    private final char prefix;

    AccountFamily(char prefix) {
        this.prefix = prefix;
    }

    /** Premier chiffre des comptes de cette classe. */
    public char prefix() {
        return prefix;
    }

    /** Clé du libellé traduit, pour les écrans et les états exportés. */
    public String messageKey() {
        return "m.acc-class-" + name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    /**
     * Classe d'un numéro de compte, ou {@code null} si le numéro ne
     * commence pas par un chiffre de 1 à 9.
     *
     * <p>Un numéro hors référentiel n'est pas rangé d'office dans une
     * classe fourre-tout : il vaut mieux une famille absente, qui se voit,
     * qu'une classification inventée, qui se propage jusque dans la
     * balance exportée.</p>
     */
    public static AccountFamily fromNumber(String number) {
        if (number == null || number.isBlank()) return null;
        char first = number.trim().charAt(0);
        for (AccountFamily family : values()) {
            if (family.prefix == first) return family;
        }
        return null;
    }
}
