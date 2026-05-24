package com.ntech.cabosse.me.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Profil de l'utilisateur connecté — renvoyé par {@code GET /api/v1/me}.
 *
 * <p>Champs sensibles volontairement exclus : {@code passwordHash},
 * {@code invitationTokenHash}, {@code invitationExpiresAt}.</p>
 */
@Schema(description = "Profil de l'utilisateur connecté")
public record MeResponseDto(
        UUID id,
        String email,
        String firstName,
        String lastName,
        String phone,
        /** {@code "fr"} ou {@code "en"} (ou {@code null} si non défini). */
        String locale,
        List<String> roles,
        UUID tenantId,
        String tenantName,
        Instant lastLoginAt
) {}
