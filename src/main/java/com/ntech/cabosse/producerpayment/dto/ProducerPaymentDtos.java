package com.ntech.cabosse.producerpayment.dto;

import com.ntech.cabosse.producerpayment.entity.ProducerPaymentEntity;
import com.ntech.cabosse.reception.entity.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Payloads et réponses des règlements aux fournisseurs. */
public final class ProducerPaymentDtos {

    private ProducerPaymentDtos() {}

    @Schema(description = "Règlement versé à un fournisseur, affecté à ses livraisons")
    public record CreatePaymentDto(
            /** Producteur bénéficiaire. Exclusif du délégué. */
            UUID memberId,
            /** Délégué bénéficiaire. Exclusif du producteur. */
            UUID delegateSupplierId,

            @NotNull(message = "Mode de paiement requis") PaymentMethod paymentMethod,
            LocalDate date,
            @Size(max = 80) String paymentRef,

            @NotEmpty(message = "Au moins une livraison à régler")
            List<@Valid AllocationDto> allocations,

            @Size(max = 1000) String notes
    ) {}

    @Schema(description = "Part du règlement affectée à une livraison")
    public record AllocationDto(
            @NotNull(message = "Livraison requise") UUID purchaseId,
            @NotNull(message = "Montant requis")
            @DecimalMin(value = "0", inclusive = false, message = "Montant > 0 requis")
            BigDecimal amountFcfa
    ) {}

    @Schema(description = "Règlement fournisseur")
    public record PaymentResponseDto(
            UUID id, String ref, LocalDate date,
            String beneficiaryKind, UUID memberId, UUID delegateSupplierId, String beneficiaryName,
            BigDecimal totalAmountFcfa,
            String paymentMethod, String paymentRef,
            List<AllocationView> allocations,
            String pieceRef, String notes, Instant createdAt, String createdByEmail
    ) {
        @Schema(description = "Livraison réglée par ce versement")
        public record AllocationView(UUID purchaseId, String purchaseRef, LocalDate purchaseDate,
                                     BigDecimal amountDueFcfa, BigDecimal amountFcfa,
                                     BigDecimal remainingAfterFcfa) {}

        public static PaymentResponseDto from(ProducerPaymentEntity e) {
            List<AllocationView> lines = e.allocations == null ? List.of()
                    : e.allocations.stream()
                            .map(a -> new AllocationView(a.purchaseId, a.purchaseRef, a.purchaseDate,
                                    a.amountDueFcfa, a.amountFcfa, a.remainingAfterFcfa))
                            .toList();
            return new PaymentResponseDto(
                    e.id, e.ref, e.date,
                    e.beneficiaryKind != null ? e.beneficiaryKind.name() : null,
                    e.memberId, e.delegateSupplierId, e.beneficiaryName,
                    e.totalAmountFcfa,
                    e.paymentMethod != null ? e.paymentMethod.name() : null,
                    e.paymentRef, lines, e.pieceRef, e.notes, e.createdAt, e.createdByEmail);
        }
    }

    /**
     * Ce que la coopérative doit encore, livraison par livraison. C'est
     * l'état que le comptable ouvre pour préparer les règlements de la
     * semaine.
     */
    @Schema(description = "Livraisons non soldées")
    public record OutstandingDto(
            BigDecimal totalRemainingFcfa,
            int beneficiaryCount,
            List<Beneficiary> beneficiaries
    ) {
        @Schema(description = "Fournisseur et ses livraisons non soldées")
        public record Beneficiary(String kind, UUID memberId, UUID delegateSupplierId,
                                  String name, BigDecimal remainingFcfa, List<Line> lines) {}

        @Schema(description = "Livraison partiellement ou non réglée")
        public record Line(UUID purchaseId, String purchaseRef, LocalDate date,
                           BigDecimal amountFcfa, BigDecimal creditImputedFcfa,
                           BigDecimal amountPaidFcfa, BigDecimal remainingFcfa) {}
    }
}
