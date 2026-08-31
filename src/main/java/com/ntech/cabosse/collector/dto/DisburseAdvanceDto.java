package com.ntech.cabosse.collector.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Remise effective des fonds au délégué.
 *
 * <p>Ces trois informations se saisissent ici, et non à la demande
 * d'avance : à la demande, personne ne sait encore de quel compte
 * l'argent sortira, ni quel numéro portera le chèque, ni ce que la
 * banque prélèvera. C'est le décaissement qui les connaît, et c'est lui
 * qui produit l'écriture.</p>
 *
 * <p>Tout est facultatif : une structure à un seul compte, qui paie en
 * espèces et ne relève aucun frais, décaisse sans rien renseigner.</p>
 */
@Schema(description = "Décaissement d'une avance approuvée")
public record DisburseAdvanceDto(

        /**
         * Moyen réellement employé, s'il diffère de celui prévu à la
         * demande.
         *
         * <p>Il vient <strong>avant</strong> le compte : c'est lui qui
         * décide des comptes éligibles, les espèces sortant d'une caisse
         * et le reste d'une banque. Choisir le compte d'abord reviendrait
         * à proposer une liste avant de savoir ce qu'elle doit contenir.</p>
         *
         * <p>Il oriente aussi le dénouement : un chèque se constate en
         * banque, des espèces en caisse.</p>
         */
        com.ntech.cabosse.reception.entity.PaymentMethod paymentMethod,

        /**
         * Caisse ou compte bancaire d'où sortent les fonds. Sans lui, le
         * moyen de paiement décide du compte par défaut.
         */
        UUID bankAccountId,

        /** Référence du règlement : numéro de chèque, de virement, de transaction. */
        @Size(max = 80) String paymentRef,

        /**
         * Frais bancaires, s'il y en a. À la charge de l'émetteur, donc de
         * la structure : ils ne touchent pas le compte courant du délégué,
         * qui reste débité du montant entier de l'avance.
         */
        @DecimalMin(value = "0", message = "{v.montant-positif-requis}")
        BigDecimal bankFeesFcfa

) {}
