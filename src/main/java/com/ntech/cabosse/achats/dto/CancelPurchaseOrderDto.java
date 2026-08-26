package com.ntech.cabosse.achats.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Payload de contre-passation d'un BC. */
@Schema(description = "Payload d'annulation/contre-passation d'un BC")
public record CancelPurchaseOrderDto(

        @NotBlank(message = "{v.raison-de-l-annulation-requise}")
        @Size(min = 5, max = 500, message = "{v.raison-entre-5-et-500-caracteres}")
        String reason

) {}
