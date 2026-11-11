package com.ntech.cabosse.auth.dto;

import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.UUID;

/**
 * Demande de passage dans une autre structure (SAAS-29).
 *
 * <p>Le jeton de rafraîchissement courant est fourni pour être fermé : la
 * session de la structure quittée ne doit pas rester ouverte dans un
 * autre onglet. Il est facultatif, un client qui ne l'a plus bascule
 * quand même.</p>
 */
@Schema(description = "Passage dans une autre structure")
public record SwitchTenantRequestDto(
        @NotNull(message = "{v.structure-requise}")
        UUID tenantId,
        String refreshToken
) {}
