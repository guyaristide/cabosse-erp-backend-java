package com.ntech.cabosse.cashsupply.dto;

import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Le refus d'une demande d'approvisionnement, et sa raison.
 *
 * <p>La raison n'est pas exigée : un refus se discute de vive voix plus
 * souvent qu'il ne s'écrit, et le réclamer ferait écrire n'importe
 * quoi.</p>
 */
@Schema(description = "Refus d'une demande d'approvisionnement de la caisse")
public record RejectCashSupplyDto(
        @Size(max = 500, message = "{v.motif-trop-long}")
        String reason
) {}
