package com.ntech.cabosse.settings.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * Les canaux par lesquels l'éditeur apprend ce qui se passe à
 * l'assistance : vue admin, sans jamais un secret en clair.
 */
@Schema(description = "Canaux d'alerte de l'éditeur")
public record EditorAlertsDto(
        List<Channel> channels,
        String updatedAt,
        String updatedBy
) {
    public record Channel(
            String id,
            /** EMAIL, SLACK ou WEBHOOK. */
            String type,
            String label,
            /** L'adresse, pour un courriel seulement ; une URL de webhook ne ressort jamais. */
            String target,
            boolean targetSet,
            boolean enabled,
            /** TICKET_OPENED, TENANT_REPLIED, TICKET_RESOLVED. */
            List<String> events
    ) {}
}
