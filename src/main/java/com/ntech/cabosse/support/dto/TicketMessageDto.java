package com.ntech.cabosse.support.dto;

import com.ntech.cabosse.support.entity.TicketAuthorRole;
import com.ntech.cabosse.support.entity.TicketMessageEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Un message du fil d'un ticket")
public record TicketMessageDto(
        UUID id,
        String body,
        String authorName,
        TicketAuthorRole authorRole,
        boolean internal,
        Instant createdAt
) {
    public static TicketMessageDto from(TicketMessageEntity m) {
        return new TicketMessageDto(m.id, m.body, m.authorName, m.authorRole, m.internal, m.createdAt);
    }
}
