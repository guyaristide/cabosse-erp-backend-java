package com.ntech.cabosse.collector.dto;

import com.ntech.cabosse.reception.entity.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Paiement d'un reliquat approuvé (CE-187). La contrepartie suit le moyen
 * réel : compte banque pour un chèque ou un virement, compte caisse pour
 * une pièce de caisse. La référence du règlement est exigée, c'est elle
 * que le rapprochement retrouvera.
 */
@Schema(description = "Paiement d'un reliquat d'avance approuvé")
public record PayAdvanceRefundDto(
        @NotNull(message = "{v.moyen-paiement-requis}") PaymentMethod paymentMethod,
        /** Caisse ou compte bancaire mouvementé. Vide : défaut du moyen. */
        UUID bankAccountId,
        @NotNull(message = "{v.reference-reglement-requise}")
        @Size(max = 80) String paymentRef,
        @DecimalMin(value = "0", message = "{v.frais-bancaires-positifs}")
        BigDecimal bankFees,
        /** Mot de la caissière : son accusé de l'avis favorable (V2). */
        @Size(max = 500) String note
) {}
