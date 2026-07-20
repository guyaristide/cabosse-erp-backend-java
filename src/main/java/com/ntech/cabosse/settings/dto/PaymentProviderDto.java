package com.ntech.cabosse.settings.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Une passerelle de paiement configurable. La section porte le code
 * (ex. {@code "wave"}, {@code "orange-money"}). Le statut {@code enabled}
 * gouverne si la passerelle est proposée aux tenants.
 */
@Schema(description = "Passerelle de paiement : vue admin")
public record PaymentProviderDto(
        /** Slug, ex. {@code wave}, {@code orange-money}, {@code mtn}, {@code cinetpay}. */
        String code,
        String name,
        boolean enabled,
        String apiBaseUrl,
        String merchantId,
        /** Toujours masqué. */
        String apiKeyMasked,
        boolean apiKeySet,
        String webhookSecretMasked,
        boolean webhookSecretSet,
        EmailSettingsDto.Source source,
        String updatedAt,
        String updatedBy
) {}
