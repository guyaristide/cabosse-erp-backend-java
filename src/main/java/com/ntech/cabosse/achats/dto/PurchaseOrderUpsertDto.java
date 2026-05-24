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

        @NotNull(message = "Fournisseur requis")
        UUID supplierId,

        @NotNull(message = "Date de commande requise")
        LocalDate orderDate,

        LocalDate deliveryDate,
        LocalDate invoiceDate,

        @Size(max = 80, message = "Numéro de facture trop long")
        String invoiceNumber,

        @Size(max = 120)
        String paymentTerms,

        @NotEmpty(message = "Au moins une ligne requise")
        List<@Valid PurchaseOrderLineDto> lines,

        @DecimalMin(value = "0", message = "Transport négatif interdit")
        BigDecimal transportFcfa,

        @NotNull(message = "Taux de TVA requis")
        @DecimalMin(value = "0", message = "TVA négative interdite")
        @DecimalMax(value = "100", message = "TVA supérieure à 100 % interdite")
        BigDecimal vatRatePct,

        @Size(max = 2000, message = "Notes trop longues (2000 caractères max)")
        String notes

) {}
