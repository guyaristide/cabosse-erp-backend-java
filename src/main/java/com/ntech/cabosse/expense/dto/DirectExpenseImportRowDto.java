package com.ntech.cabosse.expense.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Une ligne du fichier de dépenses, telle qu'elle est lue.
 *
 * <p>Tout en texte : le fichier vient d'un tableur, où un montant peut
 * s'écrire avec une virgule et une date dans l'ordre du pays. Le refus
 * se décide à la vérification, sur une valeur qu'on a su lire.</p>
 */
@Schema(description = "Ligne brute d'un fichier de dépenses")
public record DirectExpenseImportRowDto(
        int rowNumber,
        /** « Abonnement » ou « Petite dépense », ou les codes CONTRACT / PETTY_CASH. */
        String kind,
        String expenseDate,
        /** Nom du prestataire. Inconnu, la dépense reste au collectif fournisseurs. */
        String supplierName,
        String expenseTypeName,
        String chargeAccount,
        String label,
        String periodLabel,
        String amountHt,
        String vatRatePct,
        String allocationKeyCode,
        String notes
) {}
