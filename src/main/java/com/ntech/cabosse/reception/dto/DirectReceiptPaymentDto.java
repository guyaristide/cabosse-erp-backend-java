package com.ntech.cabosse.reception.dto;

import com.ntech.cabosse.reception.entity.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Paiement d'une ligne de réception directe. Tous les champs sont
 * optionnels au sens où, à la création, le client peut omettre ce DTO
 * (paiement différé). Quand fourni, {@code amountFcfa} est obligatoire.
 */
@Schema(description = "Paiement d'une ligne de réception directe")
public record DirectReceiptPaymentDto(

        LocalDate paidOn,

        @DecimalMin(value = "0", inclusive = false, message = "Montant > 0 requis")
        BigDecimal amountFcfa,

        @Size(max = 80, message = "Référence du bon de paiement trop longue")
        String paymentNoteRef,

        PaymentMethod method,

        @Size(max = 500)
        String notes

) {}
