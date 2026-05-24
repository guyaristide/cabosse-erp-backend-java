package com.ntech.cabosse.auth.dto;

import jakarta.validation.constraints.NotBlank;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Payload de révocation d'un refresh token")
public record LogoutRequestDto(

        @NotBlank(message = "refreshToken requis")
        String refreshToken

) {}
