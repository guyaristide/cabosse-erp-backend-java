package com.ntech.cabosse.producerpurchase.dto;

import com.ntech.cabosse.reception.entity.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Payload de création d'un reçu d'achat producteur (backlog NEG-01).
 *
 * <p>Champs optionnels résolus par le service selon les préférences tenant :
 * prix garanti (campagne / saisi), montant (calculé / saisi), poids (pesé /
 * dérivé des sacs), site (actif surchargeable). Le produit vient de la liste
 * de la coopérative et est résolu vers l'article matière première lié.</p>
 */
@Schema(description = "Création d'un reçu d'achat producteur")
public record ProducerPurchaseUpsertDto(
        @NotNull LocalDate date,
        @NotNull UUID memberId,
        @NotBlank @Size(max = 24) String productCode,

        UUID siteId,
        UUID campaignId,

        Integer nbSacs,
        @DecimalMin("0.0") BigDecimal weightKg,
        @DecimalMin("0.0") BigDecimal guaranteedPricePerKgFcfa,
        @DecimalMin("0.0") BigDecimal amountFcfa,

        @NotNull PaymentMethod paymentMethod,
        @Size(max = 80) String paymentRef,

        UUID payerMemberId,
        @Size(max = 120) String payerName,

        UUID collectorAdvanceId
) {}
