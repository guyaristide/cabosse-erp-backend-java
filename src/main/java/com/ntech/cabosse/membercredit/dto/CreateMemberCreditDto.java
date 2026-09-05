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
        @NotNull(message = "{v.producteur-requis}") UUID memberId,
        @NotNull(message = "{v.nature-requise}") MemberCreditKind kind,

        @NotNull(message = "{v.montant-requis}")
        @DecimalMin(value = "0", inclusive = false, message = "{v.montant-0-requis}")
        BigDecimal amount,

        @Size(max = 200, message = "{v.objet-trop-long}") String purpose,
        /** Contrepartie attendue, proposée au barème et saisie par la coop. */
        @DecimalMin(value = "0", inclusive = false, message = "{v.quantite-0-requise}")
        BigDecimal expectedQuantity,
        /**
         * Montant accordé quand le directeur tranche au dépôt. Absent : le
         * montant sollicité est accordé en entier, cas courant.
         */
        @DecimalMin(value = "0", inclusive = false, message = "{v.montant-0-requis}")
        BigDecimal approvedAmount,
        LocalDate requestedAt,
        UUID campaignId,
        @Size(max = 1000) String notes
) {}
