package com.ntech.cabosse.logs.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Le manifeste de l'archive des journaux ({@code manifest.json}).
 *
 * <p>Il dit de quelle structure et de quelle période l'archive parle, qui
 * l'a produite, avec quelle version du logiciel, et combien de lignes
 * chaque fichier porte : sans les comptes, un fichier vide se confond avec
 * un fichier manquant.</p>
 */
public record LogsArchiveManifest(
        UUID tenantId,
        String tenantSlug,
        String tenantName,
        Instant from,
        Instant to,
        Instant generatedAt,
        String generatedBy,
        String applicationVersion,
        Map<String, Long> counts
) {}
