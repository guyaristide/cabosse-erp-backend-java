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

        /** N° du reçu officiel remis au producteur. */
        @Size(max = 60) String officialReceiptRef,

        /**
         * Code externe du producteur effectivement porté par le document.
         * Vide : le code de référence du tenant, à défaut le premier connu.
         */
        @Size(max = 60) String producerExternalCode,

        @NotNull UUID memberId,
        @NotNull UUID articleId,

        UUID siteId,
        UUID campaignId,

        Integer nbSacs,
        @DecimalMin("0.0") BigDecimal weightKg,
        @DecimalMin("0.0") BigDecimal guaranteedPricePerKgFcfa,
        @DecimalMin("0.0") BigDecimal amountFcfa,
        /** Montant remis au producteur. Vide : le montant dû est réputé payé. */
        @DecimalMin("0.0") BigDecimal amountPaidFcfa,

        @NotNull PaymentMethod paymentMethod,
        @Size(max = 80) String paymentRef,

        UUID payerMemberId,
        @Size(max = 120) String payerName,

        /** Délégué collecteur dont le compte porte ce reçu. */
        UUID delegateSupplierId,

        /** Bordereau de livraison (regroupement). Attribué à l'import. */
        @Size(max = 40) String deliveryRef
) {}
