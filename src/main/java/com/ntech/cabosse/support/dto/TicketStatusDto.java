package com.ntech.cabosse.support.dto;

import com.ntech.cabosse.support.entity.TicketStatus;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Nouveau statut d'un ticket")
public record TicketStatusDto(
        @NotNull(message = "{v.statut-requis}") TicketStatus status
) {}
