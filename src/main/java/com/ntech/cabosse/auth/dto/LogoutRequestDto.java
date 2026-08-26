package com.ntech.cabosse.auth.dto;

import jakarta.validation.constraints.NotBlank;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Payload de révocation d'un refresh token")
public record LogoutRequestDto(

        @NotBlank(message = "{v.refreshtoken-requis}")
        String refreshToken

) {}
