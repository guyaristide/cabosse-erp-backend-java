package com.ntech.cabosse.shared.exception;

/**
 * Conflit de données détecté (duplicat, ressource déjà dans l'état cible,
 * collision optimiste sur version). Mappée vers {@code HTTP 409 Conflict}.
 */
public class ConflictException extends RuntimeException {

    private final ErrorCode errorCode;

    public ConflictException(String message) {
        this(ErrorCode.CONFLICT, message);
    }

    /**
     * Conflit qualifié : le code dit à une file de rejeu de quoi il
     * retourne, là où le message français ne s'adresse qu'à l'humain.
     */
    public ConflictException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode != null ? errorCode : ErrorCode.CONFLICT;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
