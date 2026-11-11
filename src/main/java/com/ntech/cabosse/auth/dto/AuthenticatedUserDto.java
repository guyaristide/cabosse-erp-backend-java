package com.ntech.cabosse.auth.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Set;
import java.util.UUID;

/**
 * Profil minimal du user connecté, retourné dans {@link LoginResponseDto}
 * pour éviter au frontend de devoir appeler {@code GET /users/me} juste
 * après le login.
 *
 * <p>Ne contient <strong>jamais</strong> le hash de mot de passe ni
 * d'information sensible.</p>
 */
@Schema(description = "Profil minimal de l'utilisateur authentifié")
public record AuthenticatedUserDto(

        UUID id,
        String email,
        String firstName,
        String lastName,
        UUID tenantId,
        String tenantName,
        @Schema(description = "Rôles dans la structure courante, rôle de plateforme compris")
        Set<String> roles,

        /**
         * Les structures où ce compte peut entrer, la courante comprise
         * (SAAS-29). Une seule entrée pour la plupart des comptes ; le
         * sélecteur ne s'affiche qu'au-delà.
         */
        @Schema(description = "Structures accessibles, la courante comprise")
        java.util.List<AccessibleTenantDto> tenants

) {}
