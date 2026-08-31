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
        /**
         * Numéro du compte, sans espace, commençant par un chiffre.
         *
         * <p>Six chiffres pour un compte du plan et pour ses sous-comptes
         * ({@code 471100} débiteurs divers, {@code 471110} débiteurs
         * divers-délégués). Les comptes rattachés à un tiers descendent
         * plus loin ({@code 47111001} pour un délégué), d'où la longueur
         * ouverte jusqu'à vingt caractères.</p>
         *
         * <p>Des chiffres, et rien d'autre. Le premier donne la classe
         * SYSCOHADA du compte ; une lettre au milieu casserait le tri du
         * plan et la dérivation du rattachement, qui lisent le numéro
         * comme une suite de rangs. Les noms des tiers vivent dans
         * l'intitulé, pas dans le numéro.</p>
         */
        @NotBlank
        @Pattern(regexp = "\\d{3,20}", message = "{v.numero-de-compte-invalide}")
        String number,

        @NotBlank @Size(min = 2, max = 120) String label
) {}
