package com.ntech.cabosse.iddocument.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Payload d'écriture d'un type de pièce d'identité")
public record IdDocumentTypeUpsertDto(
        @Pattern(regexp = "^$|^[a-z0-9-]{2,60}$",
                message = "Code type de pièce : minuscules, chiffres, tirets")
        String code,

        @NotBlank @Size(min = 1, max = 120)
        String name
) {}
