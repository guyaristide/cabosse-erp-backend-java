package com.ntech.cabosse.tenant.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Payload d'invitation d'un nouvel utilisateur sur un tenant existant.
 *
 * <p>Le rôle est limité au noyau humain ({@code TENANT_ADMIN},
 * {@code USER}). Les rôles {@code PLATFORM_ADMIN} et {@code SYSTEM} ne
 * sont jamais invitables depuis cet endpoint (raison de sécurité — un
 * platform admin se crée via {@code BOOTSTRAP_ADMIN_*} ou
 * promotion explicite, jamais via une UI tenant).</p>
 */
@Schema(description = "Payload d'invitation d'un utilisateur sur un tenant")
public record InviteTenantUserPayloadDto(

        @NotBlank(message = "E-mail requis")
        @Email(message = "Adresse e-mail invalide")
        String email,

        @NotBlank(message = "Prénom requis")
        @Size(min = 2, max = 80, message = "Prénom entre 2 et 80 caractères")
        String firstName,

        @NotBlank(message = "Nom requis")
        @Size(min = 2, max = 80, message = "Nom entre 2 et 80 caractères")
        String lastName,

        @NotBlank(message = "Rôle requis")
        @Pattern(regexp = "^(TENANT_ADMIN|USER)$",
                message = "Rôle invalide : TENANT_ADMIN ou USER attendu")
        String role

) {}
