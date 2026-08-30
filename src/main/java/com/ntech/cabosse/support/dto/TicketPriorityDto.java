package com.ntech.cabosse.support.dto;

import com.ntech.cabosse.support.entity.TicketPriority;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Nouvelle priorité d'un ticket")
public record TicketPriorityDto(
        @NotNull(message = "{v.priorite-requise}") TicketPriority priority
) {}
