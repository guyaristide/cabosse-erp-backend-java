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

        // Même règle que le plan comptable : la longueur est libre, et
        // certaines structures travaillent à huit chiffres (expert,
        // 12/09/2026). Deux règles divergentes faisaient refuser ici un
        // compte que le plan acceptait.
        @Pattern(regexp = "^$|^\\d{3,20}$", message = "{v.numero-de-compte-invalide}")
        String syscohadaAccount
) {}
