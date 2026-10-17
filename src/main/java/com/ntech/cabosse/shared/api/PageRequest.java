package com.ntech.cabosse.shared.api;

import jakarta.ws.rs.BadRequestException;

/**
 * Paramètres de pagination normalisés d'un endpoint de liste
 * ({@code ?page=&perPage=}), validés selon les bornes du CLAUDE.md §10.4 :
 * défaut 20, maximum 100 — toute valeur supérieure est rejetée en
 * {@code 400 Bad Request} (pas de "give me all" sur une API publique).
 *
 * <p>{@code page} est 0-based ; une valeur négative est ramenée à 0.</p>
 */
public record PageRequest(int page, int perPage, String sort, String direction) {

    public static final int DEFAULT_PER_PAGE = 20;
    public static final int MAX_PER_PAGE = 100;

    public static PageRequest of(int page, int perPage) {
        return of(page, perPage, null, null);
    }

    /**
     * Pagination avec le tri demandé.
     *
     * <p>Le nom de colonne n'est pas une clé Mongo : il est traduit, et
     * surtout filtré, par la liste des colonnes triables que chaque
     * endpoint déclare ({@link SortFields}). Passer directement au
     * moteur ce que la requête envoie ferait trier sur un champ sans
     * index, c'est-à-dire parcourir toute la collection, et exposerait
     * la forme interne des documents.</p>
     */
    public static PageRequest of(int page, int perPage, String sort, String direction) {
        if (perPage > MAX_PER_PAGE) {
            throw new BadRequestException(
                    "perPage maximum : " + MAX_PER_PAGE + " (reçu : " + perPage + ").");
        }
        int safePerPage = perPage <= 0 ? DEFAULT_PER_PAGE : perPage;
        return new PageRequest(Math.max(0, page), safePerPage,
                blankToNull(sort), blankToNull(direction));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Offset Mongo ({@code skip}) correspondant à la page demandée. */
    public int skip() {
        return page * perPage;
    }
}
