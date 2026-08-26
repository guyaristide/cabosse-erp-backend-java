package com.ntech.cabosse.shared.export;

import java.text.Normalizer;
import java.util.Locale;
import java.util.function.Function;

/**
 * Définition d'une colonne d'export : identifiant stable, libellé visible,
 * nature de la valeur, extracteur depuis une ligne de type {@code T}.
 *
 * <p><strong>Pourquoi une clé en plus du libellé.</strong> Le libellé
 * servait aussi d'identifiant : la sélection de colonnes envoyée par le
 * front comparait des libellés français, et la mise en forme des cellules
 * Excel était devinée en cherchant des mots français dans l'en-tête. Deux
 * conséquences, avant même toute traduction : renommer un libellé cassait
 * la sélection enregistrée, et une colonne dont le titre ne contenait
 * aucun mot connu (« Poids net », « Superficie », « Rendement ») était
 * traitée comme un montant, donc <em>arrondie à l'entier</em>. La clé rend
 * la sélection insensible à la langue et aux reformulations ; le
 * {@link ColumnKind} déclaré évite de deviner ce que le code sait déjà.</p>
 *
 * <p>L'extracteur renvoie un objet : les writers CSV/XLSX/PDF ont chacun
 * leur stratégie de formatage. Idée :</p>
 * <ul>
 *   <li>{@code null} → cellule vide.</li>
 *   <li>{@code Number} / {@code BigDecimal} → numéro typé en XLSX.</li>
 *   <li>{@code LocalDate} / {@code Instant} → date typée en XLSX.</li>
 *   <li>Tout autre objet → {@code toString()}.</li>
 * </ul>
 *
 * @param key      identifiant stable, indépendant de la langue
 * @param header   libellé affiché, traduisible
 * @param kind     nature déclarée ; {@code null} laisse le writer déduire
 * @param <T>      type de la ligne source (entité ou DTO)
 */
public record ExportColumn<T>(
        String key,
        String header,
        ColumnKind kind,
        Function<T, Object> extractor
) {

    /**
     * Colonne dont la clé est dérivée du libellé.
     *
     * <p>Forme de transition : elle garde les quelque cinq cents colonnes
     * existantes fonctionnelles sans les réécrire d'un coup. La clé reste
     * alors figée sur le libellé français d'origine, ce qui suffit à rendre
     * la sélection insensible à la traduction, mais pas aux reformulations.
     * Une colonne qui porte un enjeu de format déclare sa clé et sa nature
     * explicitement.</p>
     */
    public static <T> ExportColumn<T> of(String header, Function<T, Object> extractor) {
        return new ExportColumn<>(slug(header), header, null, extractor);
    }

    /** Colonne à clé explicite, nature déduite par le writer. */
    public static <T> ExportColumn<T> of(String key, String header, Function<T, Object> extractor) {
        return new ExportColumn<>(key, header, null, extractor);
    }

    /** Colonne entièrement déclarée : ni la clé ni le format ne se devinent. */
    public static <T> ExportColumn<T> of(String key, String header, ColumnKind kind,
                                         Function<T, Object> extractor) {
        return new ExportColumn<>(key, header, kind, extractor);
    }

    /**
     * Identifiant dérivé d'un libellé : sans accent, sans ponctuation, en
     * minuscules séparées par des tirets. « Montant HT » donne
     * {@code montant-ht}, « Fèves fraîches (kg) » donne
     * {@code feves-fraiches-kg}.
     */
    static String slug(String header) {
        if (header == null || header.isBlank()) return "";
        String normalized = Normalizer.normalize(header, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return normalized;
    }
}
