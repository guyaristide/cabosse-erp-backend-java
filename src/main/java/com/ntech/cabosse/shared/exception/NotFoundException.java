package com.ntech.cabosse.shared.exception;

/**
 * Ressource demandée introuvable. Mappée vers {@code HTTP 404 Not Found}.
 *
 * <p>Le message doit identifier le type de ressource sans exposer
 * d'informations sensibles. Convention : {@code "Tenant abc-123
 * introuvable"}.</p>
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
