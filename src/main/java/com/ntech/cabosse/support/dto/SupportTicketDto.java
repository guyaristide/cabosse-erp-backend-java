package com.ntech.cabosse.support.dto;

import com.ntech.cabosse.support.entity.SupportTicketEntity;
import com.ntech.cabosse.support.entity.TicketCategory;
import com.ntech.cabosse.support.entity.TicketPriority;
import com.ntech.cabosse.support.entity.TicketStatus;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Un ticket tel qu'il sort de l'API.
 *
 * <p>Deux fabriques, et c'est intentionnel : {@link #forStaff} rend le fil
 * entier, {@link #forTenant} en retire les notes internes. Le tri se fait
 * ici, à la composition de la réponse, et non à l'affichage — une note
 * masquée dans un écran reste lisible par quiconque ouvre la réponse de
 * l'API.</p>
 */
@Schema(description = "Un ticket d'assistance")
public record SupportTicketDto(
        UUID id,
        String ref,
        UUID tenantId,
        String tenantName,
        String subject,
        String description,
        TicketCategory category,
        TicketPriority priority,
        TicketStatus status,
        String reportedBy,
        String reportedByEmail,
        String assignedTo,
        Instant createdAt,
        Instant updatedAt,
        Instant slaDeadline,
        List<TicketMessageDto> messages
) {

    public static SupportTicketDto forStaff(SupportTicketEntity t) {
        return build(t, t.messages.stream().map(TicketMessageDto::from).toList());
    }

    public static SupportTicketDto forTenant(SupportTicketEntity t) {
        return build(t, t.messages.stream()
                .filter(m -> !m.internal)
                .map(TicketMessageDto::from)
                .toList());
    }

    private static SupportTicketDto build(SupportTicketEntity t, List<TicketMessageDto> messages) {
        return new SupportTicketDto(
                t.id, t.ref, t.tenantId, t.tenantName,
                t.subject, t.description,
                t.category, t.priority, t.status,
                t.reportedBy, t.reportedByEmail, t.assignedTo,
                t.createdAt, t.updatedAt,
                t.priority == null ? null : t.priority.deadlineFrom(t.createdAt),
                messages);
    }
}
