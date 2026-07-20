package com.ntech.cabosse.expensetype.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Payload d'écriture type de dépense")
public record ExpenseTypeUpsertDto(
        @Pattern(regexp = "^$|^[a-z0-9-]{2,40}$")
        String code,

        @NotBlank @Size(min = 2, max = 120)
        String name,

        @Size(max = 500)
        String description,

        @Size(max = 40)
        String category,

        @Pattern(regexp = "^$|^\\d{2,8}$", message = "Compte SYSCOHADA : 2 à 8 chiffres")
        String syscohadaAccount
) {}
