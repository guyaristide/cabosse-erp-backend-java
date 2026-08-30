package com.ntech.cabosse.support.dto;

import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Affectation d'un ticket. {@code null} le remet dans la file. */
@Schema(description = "Affectation d'un ticket")
public record AssignTicketDto(
        @Size(max = 100, message = "{v.nom-trop-long-100-caracteres-max}") String assignee
) {}
