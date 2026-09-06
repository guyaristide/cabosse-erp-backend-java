package com.ntech.cabosse.notification.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Un événement du catalogue et sa règle effective, pour l'écran de
 * réglage. {@code label} et {@code defaultAudienceLabel} sont servis
 * dans la langue de la requête.
 */
public record NotificationRuleDto(
        String eventCode,
        String label,
        boolean audienceConfigurable,
        String defaultAudienceLabel,
        boolean enabled,
        List<String> channels,
        List<UUID> recipientRoleIds,
        List<String> ccEmails,
        boolean customized,
        Instant updatedAt,
        String updatedByEmail
) {}
