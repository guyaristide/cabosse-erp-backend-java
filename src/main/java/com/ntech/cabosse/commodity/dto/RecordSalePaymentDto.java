package com.ntech.cabosse.commodity.dto;

import com.ntech.cabosse.reception.entity.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Constat d'un encaissement client (CE-194, page 3 du modèle) : la date
 * d'encaissement se saisit, le montant aussi (un règlement partiel laisse
 * le solde au client), et la référence du chèque ou du virement est
 * exigée, c'est elle que le rapprochement retrouvera.
 */
@Schema(description = "Encaissement client sur une vente négoce")
public record RecordSalePaymentDto(
        @NotNull LocalDate paidOn,
        @NotNull(message = "{v.montant-requis}")
        @DecimalMin(value = "0", inclusive = false, message = "{v.montant-0-requis}")
        BigDecimal amount,
        @NotNull(message = "{v.moyen-paiement-requis}") PaymentMethod method,
        UUID bankAccountId,
        @NotNull(message = "{v.reference-reglement-requise}")
        @Size(max = 80) String paymentRef,
        @Size(max = 500) String notes
) {}
