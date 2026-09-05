package com.ntech.cabosse.achats.dto;

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
 * Payload d'écriture (création ou édition brouillon) d'un BC. La
 * référence ({@code ref}) est générée serveur, le statut transite via
 * des endpoints dédiés — aucun des deux n'est dans ce payload.
 */
@Schema(description = "Payload d'écriture d'un BC")
public record PurchaseOrderUpsertDto(

        @NotNull(message = "{v.fournisseur-requis}")
        UUID supplierId,

        @NotNull(message = "{v.date-de-commande-requise}")
        LocalDate orderDate,

        LocalDate deliveryDate,
        LocalDate invoiceDate,

        @Size(max = 80, message = "{v.numero-de-facture-trop-long}")
        String invoiceNumber,

        @Size(max = 120)
        String paymentTerms,

        @NotEmpty(message = "{v.au-moins-une-ligne-requise}")
        List<@Valid PurchaseOrderLineDto> lines,

        @DecimalMin(value = "0", message = "{v.transport-negatif-interdit}")
        BigDecimal transport,

        @NotNull(message = "{v.taux-de-tva-requis}")
        @DecimalMin(value = "0", message = "{v.tva-negative-interdite}")
        @DecimalMax(value = "100", message = "{v.tva-superieure-a-100-interdite}")
        BigDecimal vatRatePct,

        @Size(max = 2000, message = "{v.notes-trop-longues-2000-caracteres-max}")
        String notes,

        /**
         * Si {@code true}, les frais transport seront ventilés au prorata HT
         * sur les lignes matière au moment du markDelivered et incorporés
         * au CMUP. {@code null}/{@code false} = comportement par défaut
         * (transport en dépense séparée). Gelé après DELIVERED.
         */
        Boolean incorporateFreightInCmup,

        /**
         * Surcharge ponctuelle de la préférence tenant {@code vatRecoverable}
         * pour ce BC. {@code null} (défaut) = suit la préférence tenant
         * courante au moment du markDelivered. {@code true} = la TVA de ce
         * BC est récupérable (CMUP = HT). {@code false} = la TVA de ce BC
         * est non récupérable (CMUP = TTC).
         */
        Boolean vatRecoverableOverride

) {}
