package com.ntech.cabosse.tenant.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;

/**
 * Statistique d'une collection MongoDB d'un tenant. Dérivée de
 * {@code db.runCommand({ collStats: name })}.
 */
@Schema(description = "Statistique d'une collection d'un tenant")
public record CollectionStatDto(
        String name,
        long documentCount,
        long sizeBytes,
        int indexCount,
        @Schema(description = "Dernière écriture observée : null si jamais écrit")
        Instant lastWriteAt
) {}
