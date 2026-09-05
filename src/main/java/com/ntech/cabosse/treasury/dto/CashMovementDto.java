package com.ntech.cabosse.treasury.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;

/*
 * Extrait de CashPositionDto le 04/09/2026 : un fichier .java ne porte
 * qu'un seul type, règle de la maison rappelée par l'utilisateur.
 */
/** Mouvement de caisse de la période. */
@Schema(description = "Mouvement de caisse de la période")
public record CashMovementDto(LocalDate date, String pieceRef, String libelle,
                              BigDecimal inAmount, BigDecimal outAmount) {}
