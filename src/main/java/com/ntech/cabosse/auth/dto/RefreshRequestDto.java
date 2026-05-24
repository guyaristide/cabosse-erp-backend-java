package com.ntech.cabosse.auth.dto;

import jakarta.validation.constraints.NotBlank;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Payload de rotation d'un refresh token")
public record RefreshRequestDto(

        @NotBlank(message = "refreshToken requis")
        String refreshToken

) {}
