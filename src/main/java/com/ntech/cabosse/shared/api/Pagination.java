package com.ntech.cabosse.shared.api;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Réponse paginée standard. Imbriquée dans
 * {@link ApiResponse} : le type retourné par un endpoint de liste est
 * toujours {@code ApiResponse<Pagination<T>>}, jamais {@code Pagination<T>}
 * brut, jamais {@code List<T>} brut (cf. CLAUDE.md §10.3).
 *
 * Nommage strict camelCase à l'intérieur du record (cf. §3.1).
 *
 * @param total          nombre total d'éléments matching le filtre
 * @param totalOfPages   total / perPage arrondi au sup
 * @param perPage        taille de page demandée (défaut 20, max 100, §10.4)
 * @param sorts          colonnes utilisées pour le tri
 * @param order          {@code "asc"} ou {@code "desc"}
 * @param currentPage    index 0-based
 * @param nextPage       index page suivante (ou {@code currentPage} si dernière)
 * @param previousPage   index page précédente (ou 0 si première)
 * @param filters        filtres appliqués (debug, traçabilité côté client)
 * @param items          la page de résultats
 */
public record Pagination<T>(
        Long total,
        int totalOfPages,
        Long perPage,
        String[] sorts,
        String order,
        int currentPage,
        int nextPage,
        int previousPage,
        Map<String, String> filters,
        List<T> items
) implements Serializable {}
