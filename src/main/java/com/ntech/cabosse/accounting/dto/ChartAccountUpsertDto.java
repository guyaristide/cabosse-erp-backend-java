package com.ntech.cabosse.accounting.dto;

import jakarta.validation.constraints.NotBlank;
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
/*
 * La classe SYSCOHADA n'est pas demandée : elle se déduit du premier
 * chiffre du numéro. La laisser saisir revenait à autoriser un compte
 * rangé dans la mauvaise classe, ce qui s'est produit — des comptes de
 * capital et de stock classés en charges. Une donnée qui se calcule ne
 * se saisit pas.
 */
public record ChartAccountUpsertDto(
        /** Numéro du compte. Trois à huit chiffres, sans espace. */
        @NotBlank
        @Pattern(regexp = "\\d{3,8}", message = "{validation.chart.number}")
        String number,

        @NotBlank @Size(min = 2, max = 120) String label
) {}
