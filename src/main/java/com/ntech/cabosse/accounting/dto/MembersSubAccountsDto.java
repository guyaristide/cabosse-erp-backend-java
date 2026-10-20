package com.ntech.cabosse.accounting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** La racine sous laquelle ouvrir un compte fournisseur à chaque sociétaire. */
public record MembersSubAccountsDto(
        @NotBlank
        @Pattern(regexp = "\\d{3,20}", message = "{v.numero-de-compte-invalide}")
        String root
) {}
