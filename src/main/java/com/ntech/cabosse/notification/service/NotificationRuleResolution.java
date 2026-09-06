package com.ntech.cabosse.notification.service;

import com.ntech.cabosse.notification.entity.NotificationChannel;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Ce que le routeur applique pour un événement : actif ou non, les
 * canaux, les profils destinataires (null = audience par défaut du
 * catalogue) et les copies courriel.
 */
public record NotificationRuleResolution(
        boolean enabled,
        Set<NotificationChannel> channels,
        List<UUID> recipientRoleIds,
        List<String> ccEmails
) {
    /** Le comportement d'origine : courriel + application, audience du catalogue. */
    public static NotificationRuleResolution defaults() {
        return new NotificationRuleResolution(true,
                Set.of(NotificationChannel.EMAIL, NotificationChannel.IN_APP),
                null, List.of());
    }
}
