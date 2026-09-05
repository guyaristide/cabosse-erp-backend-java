package com.ntech.cabosse.treasury.dto;

import com.ntech.cabosse.shared.api.Pagination;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;

/*
 * Extrait de son fichier-conteneur le 04/09/2026 : un fichier .java ne
 * porte qu'un seul type, règle de la maison rappelée par l'utilisateur.
 * Le propos d'ensemble du domaine vit dans le javadoc du service.
 */
/**
 * L'état et ce qu'il pèse.
 *
 * <p>Les totaux portent sur toute la période après filtrage, jamais
 * sur la page : lire un total en bas d'une première page sur dix
 * donnerait un chiffre faux à qui rend compte.</p>
 */
@Schema(description = "État des règlements exécutés sur une période")
public record SettlementReportDto(
        LocalDate from,
        LocalDate to,
        BigDecimal totalAmount,
        BigDecimal totalBankFees,
        int beneficiaryCount,
        Pagination<SettlementDto> page
) {}
