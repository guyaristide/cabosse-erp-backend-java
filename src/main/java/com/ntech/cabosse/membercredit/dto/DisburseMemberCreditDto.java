package com.ntech.cabosse.membercredit.dto;

import com.ntech.cabosse.reception.entity.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;

/** Remise effective des fonds au producteur. */
@Schema(description = "Décaissement d'un crédit approuvé")
public record DisburseMemberCreditDto(
        @NotNull(message = "Mode de paiement requis") PaymentMethod paymentMethod,
        LocalDate disbursedAt,
        @Size(max = 80) String paymentRef
) {}
