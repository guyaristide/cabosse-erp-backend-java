package com.ntech.cabosse.membercredit.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Ce qu'un producteur doit encore, au moment où on s'apprête à le payer.
 *
 * <p>C'est l'information que le comptable doit avoir sous les yeux sans
 * la chercher : sans elle, la retenue se décide de mémoire.</p>
 */
@Schema(description = "Dette d'un producteur au titre de ses crédits et avances")
public record MemberDebtDto(
        UUID memberId,
        String memberName,
        BigDecimal totalRemaining,
        List<Line> lines
) {
    @Schema(description = "Engagement décaissé qui reste à rembourser")
    public record Line(UUID creditId, String ref, String kind, String purpose,
                       LocalDate disbursedAt, BigDecimal amount,
                       BigDecimal imputedAmount, BigDecimal remaining) {}
}
