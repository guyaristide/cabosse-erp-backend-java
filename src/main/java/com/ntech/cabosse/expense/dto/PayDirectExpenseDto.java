package com.ntech.cabosse.expense.dto;

import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Le règlement d'une dépense constatée.
 *
 * <p>Le mode et le compte se choisissent au moment de payer, non à la
 * saisie : la caisse arbitre ses priorités, et le jour où la dépense
 * est constatée n'est pas celui où l'argent sort (demandé le
 * 03/10/2026).</p>
 */
@Schema(description = "Règlement d'une dépense constatée")
public record PayDirectExpenseDto(

        @Schema(description = "Date du règlement. Si omise, aujourd'hui.")
        LocalDate paidOn,

        @NotNull(message = "{v.mode-de-paiement-requis}")
        @Schema(description = "CASH, BANK_TRANSFER, CHECK, MOBILE_MONEY…")
        String paymentMethod,

        @Schema(description = "Compte bancaire mouvementé, pour un règlement non espèces")
        UUID bankAccountId,

        @Schema(description = "Montant réglé. Si omis, tout ce qui reste dû.")
        BigDecimal amount

) {}
