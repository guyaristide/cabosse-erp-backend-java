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
        /**
         * Capacités fonctionnelles effectives du tenant, sous forme de codes
         * (noms de {@code TenantCapability}, ex. {@code ["HAS_MEMBERS",
         * "HAS_COMMODITY_TRADE"]}). Dérivées par {@code TenantCapabilityService}
         * depuis la structure organisationnelle et les activités déclarées.
         *
         * <p>Contrat volontairement ouvert (liste de codes, pas d'objet de
         * booléens) : ajouter une capacité ne change pas la forme du DTO. Le
         * front teste l'appartenance au tableau.</p>
         */
        @Schema(description = "Codes des capacités fonctionnelles activées pour le tenant",
                example = "[\"HAS_MEMBERS\", \"HAS_SUSTAINABILITY\"]")
        List<String> capabilities,

        /**
         * Droits effectifs de l'utilisateur dans ce tenant (backlog ADM-01),
         * sous forme de codes de {@code Permission}.
         *
         * <p>Déjà réduits aux capacités actives : le front n'a pas à croiser
         * les deux listes, ce qui éviterait qu'un écran affiche un bouton
         * que le serveur refuserait.</p>
         */
        @Schema(description = "Codes des droits effectifs de l'utilisateur",
                example = "[\"COLLECTION_RECEIPT_WRITE\", \"STOCK_READ\"]")
        List<String> permissions,

        Instant lastLoginAt
) {
}
