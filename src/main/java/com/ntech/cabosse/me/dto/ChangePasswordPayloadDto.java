package com.ntech.cabosse.me.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Changement de mot de passe — current + new")
public record ChangePasswordPayloadDto(

        @NotBlank(message = "Mot de passe actuel requis")
        String currentPassword,

        @NotBlank(message = "Nouveau mot de passe requis")
        @Size(min = 8, max = 200, message = "Au moins 8 caractères")
        String newPassword

) {}
