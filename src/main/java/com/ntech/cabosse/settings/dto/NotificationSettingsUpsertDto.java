package com.ntech.cabosse.settings.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Payload d'écriture notifications")
public record NotificationSettingsUpsertDto(

        boolean emailEnabled,
        boolean smsEnabled,
        boolean pushEnabled,
        String smsProvider,
        String smsSenderId,
        String smsApiBaseUrl,
        /** Convention patch sensible. */
        String smsApiKey

) {}
