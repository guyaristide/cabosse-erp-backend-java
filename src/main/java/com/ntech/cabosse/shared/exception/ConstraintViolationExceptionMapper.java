package com.ntech.cabosse.shared.exception;

import com.ntech.cabosse.shared.api.ApiResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.ArrayList;
import java.util.List;

/**
 * Mappe les échecs de validation Bean Validation ({@code @NotBlank},
 * {@code @Size}, {@code @Pattern}, etc.) vers la forme {@link ApiResponse}
 * standard, avec un détail par champ dans {@code data.fieldErrors}.
 *
 * <p>Sans ce mapper, Quarkus renvoie sa réponse d'erreur par défaut
 * (forme {@code application/problem+json}) qui n'a pas le wrapper attendu
 * par le client → l'UI affichait "Bad Request" générique au lieu du
 * vrai message ("Nom requis", "E-mail invalide", etc.).</p>
 *
 * <p>Format renvoyé :</p>
 * <pre>{@code
 * {
 *   "statusCode": 400,
 *   "statusMessage": "Le formulaire contient 3 erreurs.",
 *   "data": {
 *     "fieldErrors": [
 *       { "path": "name", "message": "Nom requis" },
 *       { "path": "email", "message": "Adresse e-mail invalide" }
 *     ]
 *   }
 * }
 * }</pre>
 *
 * <p>{@code path} contient le dernier segment du chemin de propriété
 * (ex. {@code "create.payload.name"} → {@code "name"}) pour matcher
 * directement les noms de champs côté UI.</p>
 */
@Provider
public class ConstraintViolationExceptionMapper
        implements ExceptionMapper<ConstraintViolationException> {

    @Override
    public Response toResponse(ConstraintViolationException ex) {
        List<FieldError> errors = new ArrayList<>();
        for (ConstraintViolation<?> v : ex.getConstraintViolations()) {
            errors.add(new FieldError(lastSegment(v.getPropertyPath().toString()), v.getMessage()));
        }

        String summary = errors.size() == 1
                ? errors.get(0).message()
                : "Le formulaire contient " + errors.size() + " erreurs.";

        return Response
                .status(400)
                .entity(new ApiResponse<>(400, summary, new ValidationErrorPayload(errors)))
                .build();
    }

    /**
     * Extrait le dernier segment d'un chemin de propriété Bean Validation.
     * Quarkus produit typiquement {@code "update.payload.name"} ou
     * {@code "create.arg1.email"} — l'UI veut juste {@code "name"} ou
     * {@code "email"} pour brancher l'erreur au bon champ.
     */
    private static String lastSegment(String propertyPath) {
        if (propertyPath == null || propertyPath.isEmpty()) return "";
        int dot = propertyPath.lastIndexOf('.');
        return dot >= 0 ? propertyPath.substring(dot + 1) : propertyPath;
    }

    public record FieldError(String path, String message) {}

    public record ValidationErrorPayload(List<FieldError> fieldErrors) {}
}
