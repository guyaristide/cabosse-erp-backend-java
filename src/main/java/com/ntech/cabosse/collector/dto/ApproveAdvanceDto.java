package com.ntech.cabosse.collector.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Décision d'approbation d'une demande d'avance.
 *
 * <p>Approuver n'est plus un simple oui. La gouvernance peut suivre en
 * partie, et le montant qu'elle accorde devient celui qui sort de la
 * caisse. Le commentaire porte l'appréciation qui a fondé la décision,
 * particulièrement quand le montant accordé n'est pas celui demandé.</p>
 *
 * @param approvedAmount montant accordé. Absent : le montant demandé
 *                           est accordé en entier, cas courant
 * @param note               appréciation de l'approbateur, facultative
 */
@Schema(description = "Décision d'approbation d'une avance")
public record ApproveAdvanceDto(
        @Schema(description = "Montant accordé, jamais supérieur au montant demandé. "
                + "Absent : le montant demandé est accordé en entier.",
                example = "1500000")
        BigDecimal approvedAmount,

        @Schema(description = "Appréciation de l'approbateur, qui reste au dossier.")
        String note
) {}
