package com.ntech.cabosse.tenant.dto;

import com.ntech.cabosse.user.entity.UserStatus;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Vue résumée d'un utilisateur dans le contexte d'un tenant — utilisée
 * par l'onglet "Utilisateurs" du back-office plateforme.
 *
 * <p>Champs sensibles volontairement exclus : {@code passwordHash},
 * {@code invitationTokenHash}.</p>
 */
@Schema(description = "Résumé d'un utilisateur d'un tenant")
public record TenantUserSummaryDto(
        UUID id,
        String email,
        String firstName,
        String lastName,
        String phone,
        Set<String> roles,
        UserStatus status,
        Instant createdAt,
        Instant lastLoginAt,
        @Schema(description = "Date d'expiration de l'invitation si status = INVITED")
        Instant invitationExpiresAt
) {}
