package com.ntech.cabosse.region.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Payload d'écriture d'une région")
public record RegionUpsertDto(
        @Pattern(regexp = "^$|^[a-z0-9-]{2,60}$",
                message = "Code région : minuscules, chiffres, tirets")
        String code,

        @NotBlank @Size(min = 1, max = 120)
        String name
) {}
