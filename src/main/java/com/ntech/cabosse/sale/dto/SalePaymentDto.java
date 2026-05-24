package com.ntech.cabosse.sale.dto;

import com.ntech.cabosse.reception.entity.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(description = "Versement sur une vente")
public record SalePaymentDto(

        LocalDate paidOn,

        @NotNull(message = "Montant requis")
        @DecimalMin(value = "0", inclusive = false, message = "Montant > 0 requis")
        BigDecimal amountFcfa,

        @NotNull(message = "Mode de paiement requis")
        PaymentMethod method,

        @Size(max = 80) String paymentNoteRef,
        @Size(max = 500) String notes
) {}
