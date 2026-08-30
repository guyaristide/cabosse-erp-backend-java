package com.ntech.cabosse.support.entity;

import java.time.Duration;
import java.time.Instant;

/**
 * Urgence d'un ticket, de la plus forte à la plus faible.
 *
 * <p>Chaque niveau porte le délai dans lequel l'éditeur s'engage à
 * répondre. L'échéance se déduit donc de la priorité et de la date
 * d'ouverture, plutôt que d'être stockée : requalifier un ticket en P1
 * doit avancer son échéance, pas laisser traîner celle calculée à
 * l'ouverture.</p>
 */
public enum TicketPriority {

    /** Bloquant : la structure ne peut plus travailler. */
    P1(Duration.ofHours(4)),
    /** Fonction majeure dégradée, contournement possible. */
    P2(Duration.ofHours(24)),
    /** Gêne courante. */
    P3(Duration.ofHours(72)),
    /** Demande de fond, sans urgence. */
    P4(Duration.ofDays(7));

    private final Duration responseTime;

    TicketPriority(Duration responseTime) {
        this.responseTime = responseTime;
    }

    public Duration responseTime() {
        return responseTime;
    }

    public Instant deadlineFrom(Instant openedAt) {
        return openedAt == null ? null : openedAt.plus(responseTime);
    }
}
