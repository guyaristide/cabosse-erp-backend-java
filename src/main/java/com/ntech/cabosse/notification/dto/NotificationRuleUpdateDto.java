package com.ntech.cabosse.notification.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/** Réglage d'un événement de notification par l'administrateur du tenant. */
public record NotificationRuleUpdateDto(
        @Schema(description = "L'événement déclenche-t-il encore des envois.")
        Boolean enabled,
        @Schema(description = "Canaux retenus (EMAIL, IN_APP, SMS), au moins un si actif.")
        List<String> channels,
        @Schema(description = "Profils destinataires ; vide = audience par défaut (porteurs du droit).")
        List<UUID> recipientRoleIds,
        @Schema(description = "Adresses en copie, prévenues par courriel.")
        List<String> ccEmails
) {}
