package com.ntech.cabosse.shared.export;

import java.util.List;

/**
 * Tuple immuable représentant un export prêt à être sérialisé : un
 * titre lisible (servira de nom d'onglet en XLSX, de titre de page en
 * PDF), la liste des colonnes, et les lignes source.
 *
 * @param <T> type des lignes (entité ou DTO)
 */
public record ExportDataset<T>(
        String title,
        List<ExportColumn<T>> columns,
        List<T> rows,
        /**
         * Feuille d'accompagnement, XLSX seulement : un titre puis des
         * lignes de texte, pour un modèle d'import qui doit dire lesquelles
         * de ses colonnes comptent. Vide pour un export ordinaire.
         */
        Guide guide
) {
    public ExportDataset(String title, List<ExportColumn<T>> columns, List<T> rows) {
        this(title, columns, rows, null);
    }

    /** Une feuille de texte : un nom d'onglet et ses lignes, dans l'ordre. */
    public record Guide(String title, List<String> lines) {}
}
