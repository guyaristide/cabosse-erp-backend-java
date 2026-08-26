package com.ntech.cabosse.suppliercategory.dto;

import com.ntech.cabosse.suppliercategory.entity.SupplierCategoryEntity;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Payloads et réponses du référentiel des catégories de fournisseur. */
public final class SupplierCategoryDtos {

    private SupplierCategoryDtos() {}

    @Schema(description = "Payload d'écriture d'une catégorie de fournisseur")
    public record UpsertDto(
            @Pattern(regexp = "^$|^[A-Z0-9-]{2,16}$",
                    message = "{v.code-2-a-16-caracteres-majuscules-chiffres-ou-tiret}")
            String code,
            @NotBlank @Size(min = 2, max = 120) String name,
            @Size(max = 500) String description,

            /** {@code NONE}, {@code PER_KG}, {@code PERCENT} ou vide pour hériter du tenant. */
            @Pattern(regexp = "^$|^(NONE|PER_KG|PERCENT)$",
                    message = "{v.mode-de-remuneration-none-per-kg-ou-percent}")
            String marginMode,

            @DecimalMin(value = "0", message = "{v.taux-positif-ou-nul}")
            BigDecimal marginRate
    ) {}

    @Schema(description = "Catégorie de fournisseur du tenant")
    public record ResponseDto(
            UUID id, String code, String name, String description,
            String marginMode, BigDecimal marginRate,
            boolean active, Instant createdAt, Instant updatedAt
    ) {
        public static ResponseDto from(SupplierCategoryEntity e) {
            return new ResponseDto(e.id, e.code, e.name, e.description,
                    e.marginMode, e.marginRate, e.active, e.createdAt, e.updatedAt);
        }
    }

    /**
     * Ce que chaque catégorie a apporté sur une campagne, et ce qu'elle a
     * coûté en rémunération. Répond à la question de fin de campagne :
     * combien de délégués, combien de planteurs, et ce que chaque canal
     * rapporte.
     */
    @Schema(description = "État des apports et rémunérations par catégorie de fournisseur")
    public record CategoryReportDto(
            UUID campaignId, String campaignLabel,
            BigDecimal totalWeightKg, BigDecimal totalAmountFcfa, BigDecimal totalMarginFcfa,
            List<Line> lines
    ) {
        @Schema(description = "Ligne d'une catégorie, ou des fournisseurs sans catégorie")
        public record Line(
                UUID categoryId, String categoryCode, String categoryName,
                /** Fournisseurs de la catégorie ayant livré sur la période. */
                int supplierCount,
                /** Fournisseurs dont la première livraison tombe dans la période. */
                int newSupplierCount,
                int receiptCount,
                BigDecimal weightKg,
                BigDecimal amountFcfa,
                BigDecimal marginFcfa,
                /** Rémunération rapportée au kilo apporté. */
                BigDecimal marginPerKgFcfa,
                /** Part du volume total de la période. */
                BigDecimal weightSharePct
        ) {}
    }
}
