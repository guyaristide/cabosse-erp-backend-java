package com.ntech.cabosse.membercredit.dto;

import com.ntech.cabosse.reception.entity.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
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

        /** Référence du règlement : numéro de chèque, de virement, de transaction. */
        @Size(max = 80) String paymentRef,

        /**
         * Frais bancaires de l'opération, s'il y en a.
         *
         * <p>À la charge de l'émetteur, donc de la structure : ils ne
         * touchent jamais le compte du bénéficiaire, qui reste débité du
         * montant entier. Facultatif, et jamais déduit du mode de
         * paiement : « virement = frais, chèque = rien » est vrai d'une
         * banque, pas de toutes.</p>
         */
        @DecimalMin(value = "0", message = "{v.montant-positif-requis}")
        BigDecimal bankFeesFcfa
) {}
