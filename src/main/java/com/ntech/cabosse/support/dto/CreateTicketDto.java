package com.ntech.cabosse.support.dto;

import com.ntech.cabosse.support.entity.TicketCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Ouverture d'un ticket par la structure. */
@Schema(description = "Ouverture d'un ticket d'assistance")
public record CreateTicketDto(

        @NotBlank(message = "{v.objet-requis}")
        @Size(max = 160, message = "{v.objet-trop-long}")
        String subject,

        @NotBlank(message = "{v.description-requise}")
        @Size(max = 5000, message = "{v.message-trop-long}")
        String description,

        @NotNull(message = "{v.categorie-requise}")
        TicketCategory category

) {}
