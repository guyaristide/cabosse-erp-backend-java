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

        @NotNull(message = "{v.nature-requise}")
        @Pattern(regexp = "^(CONTRACT|PETTY_CASH)$", message = "{v.nature-contract-petty-cash}")
        String kind,

        LocalDate expenseDate,

        /** Prestataire / fournisseur (contrat). Facultatif pour la petite caisse. */
        UUID supplierId,

        /** Type de dépense du référentiel (résout le compte de charge). */
        UUID expenseTypeId,

        /** Compte de charge SYSCOHADA explicite (si pas de type de dépense). */
        @Pattern(regexp = "^$|^[0-9]{2,8}$", message = "{v.compte-de-charge-2-a-8-chiffres}")
        String chargeAccount,

        @NotBlank(message = "{v.libelle-requis}")
        @Size(max = 200)
        String label,

        @Size(max = 60)
        String periodLabel,

        /** Clé de répartition d'une charge indirecte (code). Vide = charge directe. */
        @Pattern(regexp = "^$|^[A-Z0-9-]{2,16}$", message = "{v.cle-2-a-16-caracteres-majuscules-chiffres-ou-tiret}")
        String allocationKeyCode,

        @NotNull(message = "{v.montant-ht-requis}")
        @DecimalMin(value = "0", inclusive = false, message = "{v.montant-ht-0-requis}")
        BigDecimal amountHtFcfa,

        @DecimalMin(value = "0", message = "{v.taux-de-tva-negatif-interdit}")
        @DecimalMax(value = "100", message = "{v.taux-de-tva-superieur-a-100-interdit}")
        BigDecimal vatRatePct,

        @NotNull(message = "{v.mode-de-reglement-requis}")
        @Pattern(regexp = "^(CASH|MOBILE_MONEY|BANK_TRANSFER|OTHER)$",
                message = "{v.reglement-cash-mobile-money-bank-transfer-other}")
        String paymentMethod,

        @Size(max = 1000)
        String notes
) {}
