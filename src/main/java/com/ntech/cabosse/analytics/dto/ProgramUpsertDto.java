package com.ntech.cabosse.analytics.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Schema(description = "Payload d'écriture d'un programme (projets inclus)")
public record ProgramUpsertDto(
        @Pattern(regexp = "^$|^[A-Z0-9-]{2,16}$",
                message = "{v.code-2-a-16-caracteres-majuscules-chiffres-ou-tiret}")
        String code,

        @NotBlank @Size(min = 2, max = 120)
        String name,

        @Size(max = 500)
        String description,

        List<@Valid ProjectPayload> projects
) {
    @Schema(description = "Projet financé d'un programme")
    public record ProjectPayload(
            @Pattern(regexp = "^$|^[A-Z0-9-]{2,16}$",
                    message = "{v.code-projet-2-a-16-caracteres-majuscules-chiffres-ou-tiret}")
            String code,
            @NotBlank @Size(min = 2, max = 120) String name,
            Boolean active
    ) {}
}
