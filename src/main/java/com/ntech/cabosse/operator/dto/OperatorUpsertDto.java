package com.ntech.cabosse.operator.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Payload d'écriture d'un opérateur")
public record OperatorUpsertDto(
        @Pattern(regexp = "^$|^[a-z0-9-]{2,60}$",
                message = "Code opérateur — minuscules, chiffres, tirets")
        String code,

        @NotBlank @Size(min = 1, max = 120)
        String name
) {}
