package com.ntech.cabosse.supplier.dto;

import com.ntech.cabosse.supplier.entity.SupplierEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Fournisseur du tenant")
public record SupplierResponseDto(
        UUID id, String code, String name,
        String legalName, String taxNumber,
        String email, String phone,
        String addressLine, String cityName, String countryCode,
        String contactName, String paymentTerms, String notes,
        boolean collector, UUID sectionId, java.util.List<UUID> localityIds,
        java.math.BigDecimal collectorMarginRate,
        String advanceAccount,
        /** Rémunération convenue campagne par campagne, la plus précise. */
        java.util.List<CampaignMarginView> collectorMarginByCampaign,
        java.math.BigDecimal collectorRetentionPerKg,
        UUID categoryId, String categoryName,
        boolean active, Instant createdAt, Instant updatedAt
) {

    public record CampaignMarginView(java.util.UUID campaignId, java.math.BigDecimal rate) {}
    public static SupplierResponseDto from(SupplierEntity e) {
        return from(e, null);
    }

    /** Variante nommant la catégorie, quand le référentiel est déjà chargé. */
    public static SupplierResponseDto from(SupplierEntity e, String categoryName) {
        return new SupplierResponseDto(
                e.id, e.code, e.name, e.legalName, e.taxNumber,
                e.email, e.phone, e.addressLine, e.cityName, e.countryCode,
                e.contactName, e.paymentTerms, e.notes,
                e.collector, e.sectionId,
                e.localityIds != null ? e.localityIds : java.util.List.of(),
                e.collectorMarginRate,
                e.advanceAccount,
                e.collectorMarginByCampaign == null ? java.util.List.of()
                        : e.collectorMarginByCampaign.stream()
                                .map(m -> new CampaignMarginView(m.campaignId, m.rate)).toList(),
                e.collectorRetentionPerKg,
                e.categoryId, categoryName,
                e.active, e.createdAt, e.updatedAt
        );
    }
}
