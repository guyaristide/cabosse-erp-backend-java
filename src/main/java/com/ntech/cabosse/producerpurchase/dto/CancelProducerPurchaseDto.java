package com.ntech.cabosse.producerpurchase.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Motif d'annulation d'un reçu d'achat producteur.
 *
 * <p>Obligatoire et un peu long : une contre-passation touche au stock, au
 * compte du délégué et à la comptabilité. Un motif d'un mot ne dit rien à
 * qui relira l'opération dans six mois.</p>
 */
@Schema(description = "Motif de la contre-passation")
public record CancelProducerPurchaseDto(
        @NotBlank(message = "{v.raison-de-l-annulation-requise}")
        @Size(min = 8, max = 500, message = "{v.raison-entre-5-et-500-caracteres}")
        String reason
) {}
