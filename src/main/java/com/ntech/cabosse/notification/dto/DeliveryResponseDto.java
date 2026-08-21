package com.ntech.cabosse.notification.dto;

import com.ntech.cabosse.notification.entity.DeliveryStatus;
import com.ntech.cabosse.notification.entity.NotificationChannel;
import com.ntech.cabosse.notification.entity.NotificationDeliveryEntity;
import com.ntech.cabosse.notification.entity.NotificationUsage;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Ligne du journal des envois. Répond à « ce message est-il parti, quand,
 * par quelle passerelle, et sinon pourquoi ».
 *
 * <p>Le corps rendu n'est pas exposé : il peut contenir un lien
 * d'activation ou un code à usage unique, que la consultation du journal
 * n'a aucune raison de révéler.</p>
 */
@Schema(description = "Ligne du journal des envois")
public record DeliveryResponseDto(UUID id,
                                  NotificationChannel channel,
                                  NotificationUsage usage,
                                  String target,
                                  String subject,
                                  String eventType,
                                  String subjectRef,
                                  DeliveryStatus status,
                                  int attempts,
                                  String providerCode,
                                  String providerMessageId,
                                  String lastError,
                                  Instant createdAt,
                                  Instant sentAt,
                                  Instant nextAttemptAt,
                                  Instant expiresAt) {

    public static DeliveryResponseDto from(NotificationDeliveryEntity e) {
        return new DeliveryResponseDto(e.id, e.channel, e.usage, e.target, e.subject,
                e.eventType, e.subjectRef, e.status, e.attempts, e.providerCode,
                e.providerMessageId, e.lastError, e.createdAt, e.sentAt,
                e.nextAttemptAt, e.expiresAt);
    }
}
