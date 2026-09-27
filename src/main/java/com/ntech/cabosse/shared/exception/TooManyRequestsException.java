package com.ntech.cabosse.shared.exception;

import java.time.Duration;

/**
 * Cadence refusée. Mappée vers {@code HTTP 429 Too Many Requests}.
 *
 * <p>Elle porte le délai d'attente : un refus sans échéance laisse
 * réessayer au hasard, et l'appelant honnête ne sait pas quand revenir.</p>
 */
public class TooManyRequestsException extends RuntimeException {

    private final Duration retryAfter;

    public TooManyRequestsException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter == null ? Duration.ZERO : retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
