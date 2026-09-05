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

        @NotNull(message = "{v.fournisseur-requis}")
        UUID supplierId,

        @NotNull(message = "{v.quantite-requise}")
        @DecimalMin(value = "0", inclusive = false, message = "{v.quantite-0-requise}")
        BigDecimal quantity,

        @NotNull(message = "{v.prix-unitaire-requis}")
        @DecimalMin(value = "0", message = "{v.prix-negatif-interdit}")
        BigDecimal unitPrice,

        @Size(max = 80, message = "{v.reference-du-bon-de-livraison-trop-longue}")
        String deliveryNoteRef,

        @Size(max = 500)
        String notes,

        /** Paiement immédiat optionnel — null si la ligne reste due. */
        @Valid
        DirectReceiptPaymentDto payment

) {}
