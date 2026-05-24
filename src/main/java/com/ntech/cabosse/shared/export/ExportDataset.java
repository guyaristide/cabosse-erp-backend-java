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
        List<T> rows
) {}
