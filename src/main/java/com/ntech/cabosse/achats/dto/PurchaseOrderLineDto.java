package com.ntech.cabosse.achats.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Ligne de BC dans le payload d'écriture. Les snapshots
 * ({@code articleCode}, {@code designation}, {@code unit}) ne sont
 * <strong>pas</strong> envoyés depuis le client — le serveur les remplit
 * depuis l'article courant à chaque sauvegarde.
 */
@Schema(description = "Ligne de bon de commande (payload écriture)")
public record PurchaseOrderLineDto(

        @NotNull(message = "articleId requis")
        UUID articleId,

        @NotNull(message = "Quantité requise")
        @DecimalMin(value = "0", inclusive = false, message = "Quantité doit être > 0")
        BigDecimal quantity,

        @NotNull(message = "Prix unitaire requis")
        @DecimalMin(value = "0", message = "Prix unitaire négatif interdit")
        BigDecimal unitPriceFcfa,

        @DecimalMin(value = "0", message = "Remise négative interdite")
        @DecimalMax(value = "100", message = "Remise supérieure à 100 % interdite")
        BigDecimal discountPct

) {}
