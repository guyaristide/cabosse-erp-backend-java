package com.ntech.cabosse.shared.trace;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Réglages des traces de requêtes ({@code cabosse.trace.*}).
 *
 * <p>Typé, comme toute configuration applicative (CLAUDE.md §2.3) : la
 * durée de rétention est relue par {@link RequestTraceRetention} pour
 * aligner l'index TTL de chaque base de structure.</p>
 */
@ConfigMapping(prefix = "cabosse.trace")
public interface TraceConfig {

    /**
     * Durée de conservation d'une trace, en jours. Deux ans par défaut :
     * c'est l'horizon d'un contrôle a posteriori sur une campagne.
     */
    @WithName("retention-days")
    @WithDefault("730")
    int retentionDays();
}
