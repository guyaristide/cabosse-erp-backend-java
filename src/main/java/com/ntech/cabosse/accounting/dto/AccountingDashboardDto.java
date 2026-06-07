package com.ntech.cabosse.accounting.dto;

import java.util.List;

/**
 * Agrégat consommé en un appel par la page Comptabilité. Évite N requêtes
 * pour composer le tableau de bord.
 *
 * <p>Le {@code reconciliation} reste vide au MVP — l'écran rapprochement
 * sera servi par un endpoint séparé lorsque l'import d'extrait bancaire
 * sera livré (sprint E).</p>
 */
public record AccountingDashboardDto(
        List<BankAccountResponseDto> accounts,
        List<CashFlowPointDto> cashFlow,
        TvaDeclarationDto tvaDeclaration,
        List<JournalPieceResponseDto> journalPieces,
        List<ChartOfAccountsResponseDto> planComptable,
        List<Object> reconciliation
) {}
