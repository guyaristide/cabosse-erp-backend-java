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
        /** Devise active du tenant (code ISO 4217, ex. {@code "XOF"}, {@code "EUR"}). */
        String tenantCurrency,
        /** Couleur primaire de marque du tenant (hex 6 chiffres, ou {@code null}). */
        String tenantBrandColor,
        /** URL relative du logo du tenant, ou {@code null} si aucun logo publié. */
        String tenantLogoUrl,
        /** Capacités filière (packs activés selon les activités du tenant). */
        TenantCapabilitiesDto capabilities,
        Instant lastLoginAt
) {

    /**
     * Drapeaux de capacités fonctionnelles du tenant. Dérivés par
     * {@code TenantCapabilityService} depuis deux sources orthogonales :
     * la structure organisationnelle (COOPERATIVE → HAS_MEMBERS) et les
     * activités économiques déclarées (chaque industry active certaines
     * capacités via son champ {@code activates}).
     *
     * <p>Le front utilise ces flags pour activer/désactiver routes,
     * sections de sidebar et composants des modules optionnels (membres,
     * parcelles, EUDR, etc.).</p>
     */
    @Schema(description = "Capacités fonctionnelles activées pour le tenant")
    public record TenantCapabilitiesDto(
            boolean hasMembers,
            boolean hasParcels,
            boolean hasFermentation,
            boolean hasDrying,
            boolean hasRoasting,
            boolean hasEudrCompliance,
            boolean hasSustainability
    ) {}
}
