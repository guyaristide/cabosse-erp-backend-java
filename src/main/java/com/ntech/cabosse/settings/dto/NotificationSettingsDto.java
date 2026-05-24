package com.ntech.cabosse.settings.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Préférences canaux de notification + config provider SMS.
 *
 * <p>Les canaux sont des feature flags globaux. Un tenant ne peut activer
 * que les canaux que la plateforme a globalement activés.</p>
 */
@Schema(description = "Paramètres notifications — vue admin")
public record NotificationSettingsDto(
        boolean emailEnabled,
        boolean smsEnabled,
        boolean pushEnabled,
        /** Provider SMS : {@code TWILIO}, {@code ORANGE}, {@code AFRICASTALKING}, etc. */
        String smsProvider,
        String smsSenderId,
        String smsApiBaseUrl,
        String smsApiKeyMasked,
        boolean smsApiKeySet,
        EmailSettingsDto.Source source,
        String updatedAt,
        String updatedBy
) {}
