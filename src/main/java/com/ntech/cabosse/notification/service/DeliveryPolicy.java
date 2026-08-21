package com.ntech.cabosse.notification.service;

import java.time.Duration;

/**
 * Règles de la file, réunies en un seul endroit.
 *
 * <p>Le plafond de tentatives est écrit <strong>une fois</strong> : sur
 * un projet voisin, la même règle vivait à deux endroits et les deux
 * valeurs avaient fini par diverger, si bien que le nombre réel de
 * tentatives ne correspondait à aucune des deux.</p>
 */
public final class DeliveryPolicy {

    private DeliveryPolicy() {}

    /** Nombre maximal de prises avant abandon définitif. */
    public static final int MAX_ATTEMPTS = 5;

    /** Durée de vie par défaut d'une ligne enfilée. */
    public static final Duration DEFAULT_TIME_TO_LIVE = Duration.ofHours(24);

    /**
     * Au-delà, une ligne prise mais jamais conclue est considérée
     * abandonnée par son envoyeur et peut être reprise.
     */
    public static final Duration RECLAIM_AFTER = Duration.ofMinutes(10);

    /** Nombre de lignes drainées par passage et par canal. */
    public static final int BATCH_SIZE = 50;

    /**
     * Retrait progressif entre deux tentatives : une minute, puis quatre,
     * puis neuf, etc. Une passerelle qui refuse tout ne doit pas être
     * matraquée, mais un incident bref doit se rattraper vite.
     */
    public static Duration backoffAfter(int attempts) {
        int n = Math.max(1, attempts);
        return Duration.ofMinutes(Math.min(60L, (long) n * n));
    }
}
