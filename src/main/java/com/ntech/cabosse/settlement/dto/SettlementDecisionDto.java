package com.ntech.cabosse.settlement.dto;

import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;

/** La décision d'un approbateur : ce qu'il accorde, et ce qu'il en dit. */
@Schema(description = "Décision sur une demande de règlement")
public record SettlementDecisionDto(
        /**
         * Le montant accordé. Absent, la demande est accordée en entier.
         * Il ne peut jamais dépasser le montant demandé : accorder plus
         * que ce qui est dû n'est pas une approbation.
         */
        BigDecimal approvedAmount,
        @Size(max = 500) String note
) {
}
