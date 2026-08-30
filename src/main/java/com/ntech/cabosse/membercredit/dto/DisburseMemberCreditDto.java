package com.ntech.cabosse.membercredit.dto;

import com.ntech.cabosse.reception.entity.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;

/** Remise effective des fonds au producteur. */
@Schema(description = "Décaissement d'un crédit approuvé")
public record DisburseMemberCreditDto(
        @NotNull(message = "{v.mode-de-paiement-requis}") PaymentMethod paymentMethod,

        /**
         * Caisse ou compte bancaire mouvementé.
         *
         * <p>Facultatif : sans lui, le mode de paiement décide du compte
         * par défaut, comme avant. Une structure qui tient plusieurs
         * caisses ou plusieurs banques sous des sous-comptes distincts le
         * renseigne pour que l'argent atterrisse au bon endroit.</p>
         */
        java.util.UUID bankAccountId,

        LocalDate disbursedAt,
        @Size(max = 80) String paymentRef
) {}
