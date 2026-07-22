package com.ntech.cabosse.me.dto;

import com.ntech.cabosse.tenant.entity.TenantProduct;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.UUID;

/** Produit vendu / collecté par la coopérative (backlog COOP-03). */
@Schema(description = "Produit vendu par la coopérative")
public record TenantProductDto(
        @Pattern(regexp = "^$|^[A-Z0-9-]{1,24}$",
                message = "Code produit : majuscules, chiffres, tiret (24 max)")
        String code,
        @NotBlank @Size(max = 120) String label,
        /** Article matière première lié (NEG-01), pour le stock/CMUP à l'achat producteur. */
        UUID articleId
) {
    public static TenantProductDto from(TenantProduct e) {
        return new TenantProductDto(e.code, e.label, e.articleId);
    }
}
