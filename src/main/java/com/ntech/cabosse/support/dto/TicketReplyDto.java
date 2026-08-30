package com.ntech.cabosse.support.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Une réponse dans le fil.
 *
 * <p>{@code internal} n'est honoré que du côté éditeur : une structure ne
 * peut pas s'écrire une note que l'éditeur ne verrait pas, la notion
 * n'aurait aucun sens pour elle.</p>
 */
@Schema(description = "Réponse à un ticket")
public record TicketReplyDto(

        @NotBlank(message = "{v.message-requis}")
        @Size(max = 5000, message = "{v.message-trop-long}")
        String body,

        boolean internal

) {}
