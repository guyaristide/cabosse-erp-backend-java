package com.ntech.cabosse.shared.exception;

/**
 * L'appelant est authentifié mais n'a pas le droit demandé. Mappée vers
 * {@code HTTP 403 Forbidden}.
 *
 * <p>À distinguer de {@link UnauthorizedException} : ici l'identité est
 * connue, c'est l'autorisation qui manque. Le message nomme le droit
 * attendu, pour que l'administrateur du tenant sache quoi accorder.</p>
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
