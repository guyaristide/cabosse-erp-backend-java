package com.ntech.cabosse.reception.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Payload de création / mise à jour d'une session de réception directe.
 * La référence ({@code ref}) et le statut sont générés / dérivés serveur.
 */
@Schema(description = "Payload d'écriture d'une session de réception directe")
public record DirectReceiptUpsertDto(

        @NotNull(message = "Article requis")
        UUID articleId,

        @NotNull(message = "Date de réception requise")
        LocalDate receivedDate,

        @Size(max = 80, message = "N° de bon de livraison de session trop long")
        String deliveryNoteRef,

        @NotEmpty(message = "Au moins une ligne requise")
        List<@Valid DirectReceiptLineDto> lines,

        @Size(max = 2000)
        String notes,

        /**
         * Taux de TVA (0–100). Optionnel, défaut 0 — la majorité des RD se
         * font auprès de producteurs paysans non assujettis. Renseigner
         * uniquement si la RD inclut une TVA facturée.
         */
        @DecimalMin(value = "0", message = "TVA négative interdite")
        @DecimalMax(value = "100", message = "TVA supérieure à 100 % interdite")
        BigDecimal vatRatePct,

        /**
         * Surcharge ponctuelle de la préférence tenant {@code vatRecoverable}
         * pour cette RD. {@code null} (défaut) = suit la préférence tenant
         * courante à la création. {@code true} = la TVA de cette RD est
         * récupérable (CMUP = HT). {@code false} = la TVA de cette RD est
         * non récupérable (CMUP = TTC). Sans effet si {@link #vatRatePct()}
         * est nul ou absent.
         */
        Boolean vatRecoverableOverride

) {}
