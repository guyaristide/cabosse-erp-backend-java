package com.ntech.cabosse.membercredit.dto;

import com.ntech.cabosse.membercredit.entity.MemberCreditKind;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Demande de crédit ou d'avance pour un producteur membre. */
@Schema(description = "Demande de crédit ou d'avance à un producteur membre")
public record CreateMemberCreditDto(
        @NotNull(message = "Producteur requis") UUID memberId,
        @NotNull(message = "Nature requise") MemberCreditKind kind,

        @NotNull(message = "Montant requis")
        @DecimalMin(value = "0", inclusive = false, message = "Montant > 0 requis")
        BigDecimal amountFcfa,

        @Size(max = 200, message = "Objet trop long") String purpose,
        LocalDate requestedAt,
        UUID campaignId,
        @Size(max = 1000) String notes
) {}
