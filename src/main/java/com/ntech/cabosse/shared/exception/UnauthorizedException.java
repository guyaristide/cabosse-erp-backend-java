package com.ntech.cabosse.shared.exception;

/**
 * Authentification absente ou invalide. Mappée vers {@code HTTP 401
 * Unauthorized}.
 *
 * <p>Pour les cas où l'utilisateur est authentifié mais n'a pas les
 * droits, le mécanisme {@code @RolesAllowed} de Quarkus retourne
 * naturellement {@code 403 Forbidden} — pas la peine de lever cette
 * exception manuellement.</p>
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
