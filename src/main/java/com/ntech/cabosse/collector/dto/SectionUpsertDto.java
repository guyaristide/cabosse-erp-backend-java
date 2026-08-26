package com.ntech.cabosse.collector.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Payload d'écriture d'une section")
public record SectionUpsertDto(
        @Pattern(regexp = "^$|^[A-Z0-9-]{2,16}$",
                message = "{v.code-2-a-16-caracteres-majuscules-chiffres-ou-tiret}")
        String code,
        @NotBlank @Size(min = 2, max = 120) String name,
        @Size(max = 500) String description
) {}
