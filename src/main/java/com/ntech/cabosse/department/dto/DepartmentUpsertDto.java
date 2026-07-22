package com.ntech.cabosse.department.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Payload d'écriture d'un département")
public record DepartmentUpsertDto(
        @Pattern(regexp = "^$|^[a-z0-9-]{2,60}$",
                message = "Code département : minuscules, chiffres, tirets")
        String code,

        @NotBlank @Size(min = 1, max = 120)
        String name
) {}
