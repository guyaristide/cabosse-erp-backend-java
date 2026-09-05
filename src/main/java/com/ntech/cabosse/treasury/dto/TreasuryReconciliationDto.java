package com.ntech.cabosse.treasury.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/*
 * Extrait de son fichier-conteneur le 04/09/2026 : un fichier .java ne
 * porte qu'un seul type, règle de la maison rappelée par l'utilisateur.
 * Le propos d'ensemble du domaine vit dans le javadoc du service.
 */
/**
 * Rapprochement entre ce qui est sorti des comptes bancaires et ce qui
 * est entré en caisse sur la période.
 */
@Schema(description = "Rapprochement banque et caisse")
public record TreasuryReconciliationDto(
        LocalDate from, LocalDate to,
        BigDecimal totalSent,
        BigDecimal totalReceived,
        BigDecimal totalInTransit,
        BigDecimal totalDiscrepancy,
        int transferCount,
        int discrepancyCount,
        List<TransferResponseDto> withDiscrepancy,
        List<TransferResponseDto> inTransit
) {}
