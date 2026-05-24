package com.ntech.cabosse.shared.audit;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Map;
import java.util.UUID;

/**
 * Vue admin d'un événement d'audit. Les champs sont stringifiés (UUID,
 * Instant) pour faciliter la consommation côté front.
 */
@Schema(description = "Événement du journal d'audit plateforme")
public record AuditEventDto(
        String id,
        String occurredAt,
        String eventType,
        String category,
        String actorEmail,
        String actorUserId,
        String targetType,
        String targetId,
        String targetLabel,
        String tenantId,
        String tenantName,
        String description,
        Map<String, Object> payload
) {

    public static AuditEventDto from(AuditEventEntity e) {
        return new AuditEventDto(
                e.id != null ? e.id.toString() : null,
                e.occurredAt != null ? e.occurredAt.toString() : null,
                e.eventType,
                e.category,
                e.actorEmail,
                e.actorUserId != null ? e.actorUserId.toString() : null,
                e.targetType,
                e.targetId,
                e.targetLabel,
                e.tenantId != null ? e.tenantId.toString() : null,
                e.tenantName,
                e.description,
                e.payload
        );
    }

    /** UUID parseur permissif — null si invalide. */
    public static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return UUID.fromString(raw); } catch (IllegalArgumentException e) { return null; }
    }
}
