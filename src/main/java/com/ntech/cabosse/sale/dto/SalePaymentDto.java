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

        @NotNull(message = "{v.montant-requis}")
        @DecimalMin(value = "0", inclusive = false, message = "{v.montant-0-requis}")
        BigDecimal amount,

        @NotNull(message = "{v.mode-de-paiement-requis}")
        PaymentMethod method,

        @Size(max = 80) String paymentNoteRef,
        @Size(max = 500) String notes
) {}
