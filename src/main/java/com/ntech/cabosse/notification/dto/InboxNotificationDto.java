package com.ntech.cabosse.notification.dto;

import com.ntech.cabosse.notification.entity.NotificationDeliveryEntity;

import java.time.Instant;
import java.util.UUID;

/**
 * Une notification de la boîte de réception de l'application.
 *
 * <p>{@code eventType} et {@code subjectRef} permettent au client de
 * mener vers l'écran concerné : le type dit le registre, la référence
 * dit la ligne.</p>
 */
public record InboxNotificationDto(
        UUID id,
        String subject,
        String body,
        String eventType,
        String subjectRef,
        Instant readAt,
        Instant createdAt
) {
    public static InboxNotificationDto from(NotificationDeliveryEntity e) {
        return new InboxNotificationDto(
                e.id, e.subject, e.body, e.eventType, e.subjectRef,
                e.readAt, e.createdAt);
    }
}
