package com.ntech.cabosse.reception.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Ligne d'une session de réception directe (1 producteur)")
public record DirectReceiptLineDto(

        @NotNull(message = "Fournisseur requis")
        UUID supplierId,

        @NotNull(message = "Quantité requise")
        @DecimalMin(value = "0", inclusive = false, message = "Quantité > 0 requise")
        BigDecimal quantity,

        @NotNull(message = "Prix unitaire requis")
        @DecimalMin(value = "0", message = "Prix négatif interdit")
        BigDecimal unitPriceFcfa,

        @Size(max = 80, message = "Référence du bon de livraison trop longue")
        String deliveryNoteRef,

        @Size(max = 500)
        String notes,

        /** Paiement immédiat optionnel — null si la ligne reste due. */
        @Valid
        DirectReceiptPaymentDto payment

) {}
