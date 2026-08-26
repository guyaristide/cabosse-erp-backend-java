package com.ntech.cabosse.sale.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Motif de contre-passation d'une vente")
public record CancelSaleDto(
        @NotBlank(message = "{v.motif-requis}")
        @Size(max = 500) String reason
) {}
