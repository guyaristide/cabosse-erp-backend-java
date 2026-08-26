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

        @NotBlank(message = "{v.e-mail-requis}")
        @Email(message = "{v.e-mail-invalide}")
        @Schema(example = "admin@cooperative.ci")
        String email,

        @NotBlank(message = "{v.mot-de-passe-requis}")
        @Size(min = 8, max = 256, message = "{v.mot-de-passe-de-8-a-256-caracteres}")
        @Schema(example = "•••••••••••")
        String password

) {}
