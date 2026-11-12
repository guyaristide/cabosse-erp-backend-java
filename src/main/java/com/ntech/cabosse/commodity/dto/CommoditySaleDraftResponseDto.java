package com.ntech.cabosse.commodity.dto;

import com.ntech.cabosse.commodity.entity.CommoditySaleDraftEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Un brouillon de vente en gros tel que l'écran le lit : son résumé pour
 * la liste, et la saisie entière pour rouvrir le formulaire (VTE-28).
 */
public record CommoditySaleDraftResponseDto(
        UUID id,
        LocalDate date,
        UUID customerId,
        String customerName,
        UUID articleId,
        String articleName,
        UUID siteId,
        String siteName,
        BigDecimal declaredKg,
        BigDecimal acceptedKg,
        List<String> dispatchNoteRefs,
        Instant createdAt,
        Instant updatedAt,
        String createdByEmail,
        String updatedByEmail,
        /** La saisie telle qu'elle a été laissée ; absente dans la liste. */
        CommoditySaleUpsertDto payload
) {
    public static CommoditySaleDraftResponseDto from(CommoditySaleDraftEntity e, CommoditySaleUpsertDto payload) {
        return new CommoditySaleDraftResponseDto(
                e.id, e.date, e.customerId, e.customerName, e.articleId, e.articleName,
                e.siteId, e.siteName, e.declaredKg, e.acceptedKg,
                e.dispatchNoteRefs == null ? List.of() : e.dispatchNoteRefs,
                e.createdAt, e.updatedAt, e.createdByEmail, e.updatedByEmail, payload);
    }
}
