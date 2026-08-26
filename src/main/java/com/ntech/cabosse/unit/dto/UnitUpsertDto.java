package com.ntech.cabosse.unit.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Payload d'écriture d'une unité de mesure")
public record UnitUpsertDto(
        @Pattern(regexp = "^$|^[A-Za-z0-9µ%/.-]{1,12}$",
                message = "{v.code-unite-1-a-12-caracteres-lettres-chiffres}")
        String code,

        @NotBlank @Size(min = 1, max = 60)
        String name
) {}
