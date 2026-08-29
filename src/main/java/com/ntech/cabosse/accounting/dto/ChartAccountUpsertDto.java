package com.ntech.cabosse.accounting.dto;

import com.ntech.cabosse.accounting.entity.AccountFamily;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Création ou modification d'un compte du plan comptable.
 *
 * <p>Le plan était figé par migrations : ouvrir une deuxième caisse ou une
 * deuxième banque supposait une livraison, alors que c'est le cas courant
 * d'une coopérative à plusieurs sites. Hors zone SYSCOHADA, ou pour un
 * cabinet qui veut une granularité fine, c'était rédhibitoire.</p>
 */
public record ChartAccountUpsertDto(
        /** Numéro du compte. Trois à huit chiffres, sans espace. */
        @NotBlank
        @Pattern(regexp = "\\d{3,8}", message = "{validation.chart.number}")
        String number,

        @NotBlank @Size(min = 2, max = 120) String label,

        @NotNull AccountFamily family
) {}
