package com.ntech.cabosse.locality.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Payload d'écriture d'une localité / village")
public record LocalityUpsertDto(
        @Pattern(regexp = "^$|^[A-Za-z0-9-]{2,60}$",
                message = "{v.code-localite-lettres-chiffres-tirets}")
        String code,

        @NotBlank @Size(min = 1, max = 120)
        String name,

        /**
         * Section dont relève la localité. Facultatif : une structure peut
         * lister ses villages avant d'avoir découpé ses sections.
         */
        java.util.UUID sectionId
) {}
