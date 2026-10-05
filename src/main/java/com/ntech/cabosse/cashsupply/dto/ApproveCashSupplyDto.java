package com.ntech.cabosse.cashsupply.dto;

import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * La décision sur une demande d'approvisionnement.
 *
 * <p>Accorder moins que demandé est une décision ordinaire quand la
 * banque ne suit pas, et le montant accordé devient celui du chèque :
 * laisser la caissière lire le montant sollicité lui ferait préparer un
 * chèque de trop.</p>
 */
@Schema(description = "Décision sur une demande d'approvisionnement de la caisse")
public record ApproveCashSupplyDto(

        @Schema(description = "Montant accordé, jamais supérieur au montant demandé. "
                + "Absent : le montant demandé est accordé en entier.")
        BigDecimal approvedAmount,

        @Size(max = 500, message = "{v.note-trop-longue}")
        @Schema(description = "Appréciation de l'approbateur, qui reste au dossier")
        String note

) {}
