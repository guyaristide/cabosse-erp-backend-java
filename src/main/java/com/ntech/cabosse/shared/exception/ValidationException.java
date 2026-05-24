package com.ntech.cabosse.shared.exception;

/**
 * Erreur de validation explicite levée par un service après contrôle
 * sémantique non couvert par Bean Validation (ex. : transition d'état
 * interdite, valeur dans une plage interdite vu un autre champ).
 *
 * <p>Mappée vers {@code HTTP 422 Unprocessable Entity}. Pour les
 * violations Bean Validation classiques ({@code @NotBlank}, {@code @Email}),
 * Hibernate Validator gère lui-même la réponse.</p>
 */
public class ValidationException extends BusinessException {

    public ValidationException(String message) {
        super(message);
    }
}
