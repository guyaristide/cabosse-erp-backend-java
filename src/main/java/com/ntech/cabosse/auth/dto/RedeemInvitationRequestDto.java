package com.ntech.cabosse.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Payload pour activer un compte invité ou réinitialiser un mot de passe.
 * Le {@code token} provient du lien envoyé par mail
 * ({@code /invitation/<token>}) — il est haché en SHA-256 et comparé au
 * {@code invitationTokenHash} stocké sur le user.
 *
 * <p>Une fois validé, le mot de passe fourni remplace l'ancien hash,
 * le statut user passe à {@code ACTIVE} et le token est effacé (à usage
 * unique).</p>
 */
@Schema(description = "Activation d'un compte invité / reset de mot de passe")
public record RedeemInvitationRequestDto(

        @NotBlank(message = "{v.token-requis}")
        String token,

        @NotBlank(message = "{v.mot-de-passe-requis}")
        @Size(min = 8, max = 200, message = "{v.mot-de-passe-8-a-200-caracteres}")
        String password

) {}
