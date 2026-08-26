package com.ntech.cabosse.production.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Motif de contre-passation d'un OF")
public record CancelOrderDto(
        @NotBlank(message = "{v.motif-requis}")
        @Size(max = 500)
        String reason
) {}
