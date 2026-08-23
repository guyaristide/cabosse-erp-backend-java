package com.ntech.cabosse.shared.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ntech.cabosse.shared.exception.ErrorCode;

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
 * @param errorCode     motif d'échec exploitable par une machine, absent en cas de succès
 * @param retryable     réessayer plus tard peut-il aboutir ? absent en cas de succès
 * @param <T>           type de la charge utile
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        Integer statusCode,
        String statusMessage,
        T data,
        ErrorCode errorCode,
        Boolean retryable
) implements Serializable {

    /**
     * Réponse sans motif d'échec. Conserve la forme historique à trois
     * arguments : les succès n'ont rien à dire d'un code d'erreur, et les
     * champs correspondants disparaissent du JSON.
     */
    public ApiResponse(Integer statusCode, String statusMessage, T data) {
        this(statusCode, statusMessage, data, null, null);
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "OK", data);
    }

    public static <T> ApiResponse<T> created(T data) {
        return new ApiResponse<>(201, "Created", data);
    }

    public static ApiResponse<Void> noContent() {
        return new ApiResponse<>(204, "No Content", null);
    }

    /**
     * Réponse d'échec porteuse de son motif machine. Réservée aux mappers
     * d'exception : un contrôleur ne construit jamais une erreur à la main.
     */
    public static <T> ApiResponse<T> error(int status, String message, ErrorCode code) {
        return new ApiResponse<>(status, message, null, code, code.retryable());
    }
}
