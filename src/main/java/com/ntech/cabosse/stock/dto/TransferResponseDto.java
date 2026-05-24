package com.ntech.cabosse.stock.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.UUID;

/** Résultat d'un transfert : positions stock après application. */
@Schema(description = "Résultat d'un transfert inter-sites")
public record TransferResponseDto(
        UUID transferId,
        StockItemResponseDto sourceAfter,
        StockItemResponseDto destinationAfter
) {}
