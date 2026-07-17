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
public record PageRequest(int page, int perPage) {

    public static final int DEFAULT_PER_PAGE = 20;
    public static final int MAX_PER_PAGE = 100;

    public static PageRequest of(int page, int perPage) {
        if (perPage > MAX_PER_PAGE) {
            throw new BadRequestException(
                    "perPage maximum : " + MAX_PER_PAGE + " (reçu : " + perPage + ").");
        }
        int safePerPage = perPage <= 0 ? DEFAULT_PER_PAGE : perPage;
        return new PageRequest(Math.max(0, page), safePerPage);
    }

    /** Offset Mongo ({@code skip}) correspondant à la page demandée. */
    public int skip() {
        return page * perPage;
    }
}
