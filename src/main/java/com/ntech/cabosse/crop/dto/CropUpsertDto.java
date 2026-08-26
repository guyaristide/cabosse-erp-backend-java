package com.ntech.cabosse.crop.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Payload d'écriture d'une culture")
public record CropUpsertDto(
        @Pattern(regexp = "^$|^[A-Za-z0-9-]{2,60}$",
                message = "Code culture : lettres, chiffres, tirets")
        String code,

        @NotBlank @Size(min = 1, max = 120)
        String name
) {}
