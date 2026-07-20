package com.ntech.cabosse.expense.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Payload de création d'une dépense directe (ACH-03). Le compte de charge
 * provient soit du type de dépense ({@code expenseTypeId}), soit d'une
 * saisie explicite ({@code chargeAccount}) ; au moins l'un des deux.
 */
@Schema(description = "Payload de création d'une dépense directe (contrat/abonnement ou petite caisse)")
public record CreateDirectExpenseDto(

        @NotNull(message = "Nature requise")
        @Pattern(regexp = "^(CONTRACT|PETTY_CASH)$", message = "Nature : CONTRACT | PETTY_CASH")
        String kind,

        LocalDate expenseDate,

        /** Prestataire / fournisseur (contrat). Facultatif pour la petite caisse. */
        UUID supplierId,

        /** Type de dépense du référentiel (résout le compte de charge). */
        UUID expenseTypeId,

        /** Compte de charge SYSCOHADA explicite (si pas de type de dépense). */
        @Pattern(regexp = "^$|^[0-9]{2,8}$", message = "Compte de charge : 2 à 8 chiffres")
        String chargeAccount,

        @NotBlank(message = "Libellé requis")
        @Size(max = 200)
        String label,

        @Size(max = 60)
        String periodLabel,

        /** Clé de répartition d'une charge indirecte (code). Vide = charge directe. */
        @Pattern(regexp = "^$|^[A-Z0-9-]{2,16}$", message = "Clé : 2 à 16 caractères majuscules, chiffres ou tiret")
        String allocationKeyCode,

        @NotNull(message = "Montant HT requis")
        @DecimalMin(value = "0", inclusive = false, message = "Montant HT > 0 requis")
        BigDecimal amountHtFcfa,

        @DecimalMin(value = "0", message = "Taux de TVA négatif interdit")
        @DecimalMax(value = "100", message = "Taux de TVA supérieur à 100 % interdit")
        BigDecimal vatRatePct,

        @NotNull(message = "Mode de règlement requis")
        @Pattern(regexp = "^(CASH|MOBILE_MONEY|BANK_TRANSFER|OTHER)$",
                message = "Règlement : CASH | MOBILE_MONEY | BANK_TRANSFER | OTHER")
        String paymentMethod,

        @Size(max = 1000)
        String notes
) {}
