package com.ntech.cabosse.me.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Changement de mot de passe : current + new")
public record ChangePasswordPayloadDto(

        @NotBlank(message = "{v.mot-de-passe-actuel-requis}")
        String currentPassword,

        @NotBlank(message = "{v.nouveau-mot-de-passe-requis}")
        @Size(min = 8, max = 200, message = "{v.au-moins-8-caracteres}")
        String newPassword

) {}
