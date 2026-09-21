package com.ntech.cabosse.delegatestatus.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Création ou modification d'une position du référentiel. */
public record DelegateStatusUpsertDto(
        @Size(max = 40) String code,
        @NotBlank @Size(max = 120) String label,
        Boolean warning,
        Integer sortOrder,
        Boolean active) {}
