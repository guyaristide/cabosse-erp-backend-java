package com.ntech.cabosse.settings.dto;

import jakarta.validation.constraints.NotBlank;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Payload d'écriture passerelle paiement")
public record PaymentProviderUpsertDto(

        @NotBlank(message = "{v.code-requis}")
        String code,

        @NotBlank(message = "{v.nom-requis}")
        String name,

        boolean enabled,
        String apiBaseUrl,
        String merchantId,

        /** Convention patch sensible. */
        String apiKey,
        /** Convention patch sensible. */
        String webhookSecret

) {}
