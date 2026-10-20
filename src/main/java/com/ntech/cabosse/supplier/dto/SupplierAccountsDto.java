package com.ntech.cabosse.supplier.dto;

import jakarta.validation.constraints.Pattern;

/**
 * Les comptes d'un fournisseur, rattachés depuis sa fiche sans rouvrir
 * le formulaire (demandé le 20/10/2026). Un champ absent ne bouge pas,
 * une chaîne vide détache.
 */
public record SupplierAccountsDto(
        @Pattern(regexp = "^$|^[0-9]{3,20}$", message = "{v.numero-de-compte-invalide}")
        String advanceAccount,
        @Pattern(regexp = "^$|^[0-9]{2,20}$", message = "{v.numero-de-compte-invalide}")
        String subsidiaryAccount,
        @Pattern(regexp = "^$|^[0-9]{2,20}$", message = "{v.numero-de-compte-invalide}")
        String collectiveAccount
) {}
