package com.ntech.cabosse.expense.dto;

import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Le refus d'une dépense, et sa raison.
 *
 * <p>La raison n'est pas exigée : un refus se discute de vive voix plus
 * souvent qu'il ne s'écrit, et le réclamer ferait écrire n'importe
 * quoi.</p>
 */
@Schema(description = "Refus du paiement d'une dépense")
public record RejectDirectExpenseDto(
        @Size(max = 500, message = "{v.motif-trop-long}")
        String reason
) {}
