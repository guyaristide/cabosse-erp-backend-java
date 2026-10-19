package com.ntech.cabosse.accounting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Un sous-compte à ouvrir sous une racine : la racine et l'intitulé, le
 * numéro est celui que le plan attribue (demandé le 19/10/2026).
 */
public record SubAccountCreateDto(
        @NotBlank
        @Pattern(regexp = "\\d{3,20}", message = "{v.numero-de-compte-invalide}")
        String root,

        @NotBlank @Size(min = 2, max = 120) String label
) {}
