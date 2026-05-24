package com.ntech.cabosse.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Payload de connexion. Validé par Bean Validation au niveau du contrôleur
 * (cf. CLAUDE.md §8.1).
 */
@Schema(description = "Identifiants de connexion à Cabosse ERP")
public record LoginRequestDto(

        @NotBlank(message = "E-mail requis")
        @Email(message = "E-mail invalide")
        @Schema(example = "admin@cooperative.ci")
        String email,

        @NotBlank(message = "Mot de passe requis")
        @Size(min = 8, max = 256, message = "Mot de passe de 8 à 256 caractères")
        @Schema(example = "•••••••••••")
        String password

) {}
