package com.ntech.cabosse.tenant.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;

/**
 * Entrée d'historique Mongock — lue depuis {@code mongockChangeLog} de la
 * base tenant, enrichie avec les métadonnées du catalogue de migrations.
 */
@Schema(description = "Entrée d'historique Mongock pour un tenant")
public record MigrationEntryDto(

        @Schema(description = "Identifiant Mongock du changeUnit", example = "bootstrap_tenant_schema")
        String changeId,

        @Schema(description = "Ordre numérique de la migration", example = "001")
        String order,

        String author,

        @Schema(enumeration = { "success", "failed", "pending", "ignored" })
        String status,

        @Schema(description = "Date d'exécution — null si pending")
        Instant executedAt,

        @Schema(description = "Durée d'exécution en ms — null si pending")
        Long durationMs,

        @Schema(description = "Message d'erreur si status=failed")
        String errorMessage

) {}
