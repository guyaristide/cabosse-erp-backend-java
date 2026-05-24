package com.ntech.cabosse.shared.exception;

/**
 * Conflit de données détecté (duplicat, ressource déjà dans l'état cible,
 * collision optimiste sur version). Mappée vers {@code HTTP 409 Conflict}.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
