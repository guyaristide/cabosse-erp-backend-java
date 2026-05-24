package com.ntech.cabosse.shared.api;

import java.io.Serializable;

/**
 * Enveloppe de réponse standard. Toute API Cabosse ERP, sans exception,
 * retourne un {@code ApiResponse<T>} (cf. CLAUDE.md §10.2).
 *
 * Pour les réponses de type liste, on renvoie un
 * {@link com.ntech.cabosse.shared.api.Pagination Pagination&lt;T&gt;} imbriqué
 * dans {@code data}, soit le type complet
 * {@code ApiResponse<Pagination<T>>} (cf. CLAUDE.md §10.3).
 *
 * @param statusCode    code HTTP correspondant
 * @param statusMessage libellé court ("OK", "Created", "Not Found", …)
 * @param data          charge utile (peut être {@code null} pour 204)
 * @param <T>           type de la charge utile
 */
public record ApiResponse<T>(
        Integer statusCode,
        String statusMessage,
        T data
) implements Serializable {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "OK", data);
    }

    public static <T> ApiResponse<T> created(T data) {
        return new ApiResponse<>(201, "Created", data);
    }

    public static ApiResponse<Void> noContent() {
        return new ApiResponse<>(204, "No Content", null);
    }
}
