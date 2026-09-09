package com.ntech.cabosse.tenant.dto;

import jakarta.validation.constraints.Email;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Renvoi d'une invitation en attente. L'adresse est facultative : absente,
 * l'invitation repart vers la même ; fournie, elle corrige le compte
 * avant l'envoi (une faute de frappe ne doit pas condamner l'invitation).
 */
@Schema(description = "Renvoi d'invitation, avec adresse corrigée facultative")
public record ResendInvitationPayloadDto(
        @Email(message = "{v.adresse-e-mail-invalide}")
        String email
) {}
