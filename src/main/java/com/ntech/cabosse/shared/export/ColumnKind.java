package com.ntech.cabosse.shared.export;

/**
 * Nature d'une colonne d'export, qui détermine sa mise en forme.
 *
 * <p>Déclarée par la colonne quand elle la connaît, déduite sinon. La
 * déduction lit d'abord le type des valeurs, puis cherche des mots
 * français dans le libellé pour distinguer un montant d'une quantité ou
 * d'un taux. Elle marche tant que les libellés sont français et
 * contiennent l'un des mots attendus ; elle se trompe dès qu'ils sont
 * traduits, et elle se trompait déjà sur « Poids net », « Superficie » ou
 * « Rendement », classés en montant et donc <em>arrondis à l'entier</em>.
 * Une colonne qui porte des décimales a tout intérêt à déclarer sa
 * nature.</p>
 *
 * <p>Vocabulaire unique du module : ce type était l'énumération interne du
 * writer XLSX. Il est partagé plutôt que dupliqué, pour qu'une colonne
 * déclare exactement ce que le writer applique.</p>
 */
public enum ColumnKind {

    /** Texte libre, aligné à gauche. */
    TEXT,

    /** Montant : séparateur de milliers, sans décimale. */
    NUMBER_MONEY,

    /** Quantité : séparateur de milliers, décimales conservées. */
    NUMBER_QTY,

    /** Taux : suffixé du signe pourcent, sans multiplication par cent. */
    NUMBER_PCT,

    /**
     * Valeur dont la précision est significative : coordonnée GPS, taux de
     * conversion. Six décimales conservées.
     *
     * <p>Les coordonnées sortaient à trois décimales, soit une centaine de
     * mètres d'erreur, et le cycle exporter, corriger, réimporter les
     * dégradait à chaque passage.</p>
     */
    NUMBER_PRECISE,

    /**
     * Entier qui se lit comme un identifiant, non comme une quantité :
     * année, numéro d'ordre, effectif. Écrit sans séparateur de milliers.
     *
     * <p>Une année de plantation sortait « 2 003 », séparée comme un
     * montant. Redéposé, le fichier revenait avec un nombre jugé
     * illisible : nous cassions nous-mêmes l'aller-retour que l'export
     * est censé permettre. Le séparateur convient à une somme, jamais à
     * un millésime.</p>
     */
    NUMBER_INT,

    /** Date, typée comme telle dans le classeur. */
    DATE
}
