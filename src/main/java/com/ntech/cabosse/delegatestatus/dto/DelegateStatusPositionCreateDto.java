package com.ntech.cabosse.delegatestatus.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Prise de position sur un délégué.
 *
 * <p>Le montant dû ne figure pas ici : il est calculé et écrit par le
 * serveur. Un montant fourni par l'appelant ne prouverait rien.</p>
 */
public record DelegateStatusPositionCreateDto(
        @NotNull UUID statusId,
        LocalDate effectiveDate,
        @NotBlank @Size(max = 500) String reason) {}
