package com.ntech.cabosse.cashforecast.dto;

import com.ntech.cabosse.cashforecast.entity.CashForecastEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Un prévisionnel, avec ce que ses lignes donnent une fois additionnées.
 *
 * <p>Les totaux viennent du serveur plutôt que de l'écran : trois
 * endroits qui additionneraient les mêmes lignes finiraient par
 * annoncer trois chiffres.</p>
 */
@Schema(description = "Prévisionnel de décaissement d'un mois")
public record CashForecastResponseDto(
        UUID id,
        String month,
        String status,
        List<CashForecastLineDto> lines,
        BigDecimal openingCash,
        BigDecimal openingBank,
        BigDecimal openingTotal,
        BigDecimal expectedReceipts,
        BigDecimal totalOutflow,
        /** Ce qui resterait à la fin du mois si tout se passait comme prévu. */
        BigDecimal projectedBalance,
        Instant createdAt,
        String createdByEmail,
        Instant submittedAt,
        String submittedByEmail,
        Instant decidedAt,
        String decidedByEmail,
        String rejectionReason
) {

    public static CashForecastResponseDto from(CashForecastEntity e) {
        return new CashForecastResponseDto(
                e.id, e.month, e.status == null ? null : e.status.name(),
                e.lines == null ? List.of() : e.lines.stream()
                        .map(l -> new CashForecastLineDto(
                                l.account, l.accountLabel, l.detail, l.amount, l.source))
                        .toList(),
                e.openingCash, e.openingBank, e.openingTotal(),
                e.expectedReceipts, e.totalOutflow(), e.projectedBalance(),
                e.createdAt, e.createdByEmail,
                e.submittedAt, e.submittedByEmail,
                e.decidedAt, e.decidedByEmail, e.rejectionReason);
    }
}
