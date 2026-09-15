package com.ntech.cabosse.importjournal.entity;

/**
 * Une décision que l'import a prise à la place de l'opérateur.
 *
 * <p>Valeur par défaut posée sur une colonne vide, référentiel créé au
 * passage, libellé rapproché de façon approximative, rattachement laissé
 * nul. Ces décisions sont légitimes, et c'est précisément pourquoi elles
 * ne remontent nulle part : elles n'échouent pas. Elles sont pourtant à
 * l'origine de la moitié des données fausses relevées le 15/09/2026, du
 * mode de paiement retombé sur les espèces au délégué reconnu à deux
 * lettres près.</p>
 *
 * <p>Les consigner ne change rien au traitement. Cela permet, le jour où
 * un chiffre surprend, de relire ce que l'import a supposé.</p>
 */
public class ImportDecision {

    /** Nature, en code stable : {@code DEFAULT_APPLIED}, {@code FUZZY_MATCH}, {@code REFERENCE_CREATED}, {@code LEFT_NULL}. */
    public String kind;

    /** Le champ concerné, tel qu'il s'appelle dans le fichier. */
    public String field;

    /** Ce que le fichier disait, quand il disait quelque chose. */
    public String given;

    /** Ce que l'import a retenu. */
    public String applied;

    /** Combien de lignes ont subi la même décision. */
    public int occurrences;

    public ImportDecision() {}

    public ImportDecision(String kind, String field, String given, String applied, int occurrences) {
        this.kind = kind;
        this.field = field;
        this.given = given;
        this.applied = applied;
        this.occurrences = occurrences;
    }
}
