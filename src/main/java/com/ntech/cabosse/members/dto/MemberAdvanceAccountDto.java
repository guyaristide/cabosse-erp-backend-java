package com.ntech.cabosse.members.dto;

import jakarta.validation.constraints.Pattern;

/** Le compte d'avance d'un sociétaire, rattaché depuis sa fiche. Vide détache. */
public record MemberAdvanceAccountDto(
        @Pattern(regexp = "^$|^[0-9]{3,20}$", message = "{v.numero-de-compte-invalide}")
        String advanceAccount
) {}
