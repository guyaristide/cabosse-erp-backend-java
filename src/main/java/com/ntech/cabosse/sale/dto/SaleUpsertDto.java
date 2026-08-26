package com.ntech.cabosse.sale.dto;

import com.ntech.cabosse.sale.entity.SaleChannel;
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
 * Payload de création / mise à jour d'une vente. Le {@code siteId} est
 * dans le payload pour permettre la création depuis un canal qui ne
 * dépend pas du site actif du store (rare). À défaut, le frontend
 * remplit avec le site actif.
 */
@Schema(description = "Payload d'écriture d'une vente")
public record SaleUpsertDto(

        @NotNull(message = "{v.site-requis}") UUID siteId,
        @NotNull(message = "{v.canal-requis}") SaleChannel channel,
        @NotNull(message = "{v.client-requis}") UUID customerId,

        LocalDate saleDate,
        LocalDate dueDate,
        LocalDate deliveryDate,

        @NotEmpty(message = "{v.au-moins-une-ligne-requise}")
        List<@Valid SaleLineDto> lines,

        /** Remise globale 0..100. */
        @DecimalMin(value = "0", message = "{v.pourcentage-negatif-interdit}") @DecimalMax(value = "100", message = "{v.pourcentage-superieur-a-100}") BigDecimal discountPct,
        /** Taux TVA 0..100. */
        @DecimalMin(value = "0", message = "{v.pourcentage-negatif-interdit}") @DecimalMax(value = "100", message = "{v.pourcentage-superieur-a-100}") BigDecimal vatRatePct,

        @Size(max = 80) String invoiceNumber,
        @Size(max = 2000) String notes

) {}
