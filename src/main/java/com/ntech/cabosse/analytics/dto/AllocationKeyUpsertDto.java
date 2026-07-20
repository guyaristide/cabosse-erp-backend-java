package com.ntech.cabosse.analytics.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "Payload d'écriture d'une clé de répartition")
public record AllocationKeyUpsertDto(
        @Pattern(regexp = "^$|^[A-Z0-9-]{2,16}$",
                message = "Code : 2 à 16 caractères majuscules, chiffres ou tiret")
        String code,

        @NotBlank @Size(min = 2, max = 120)
        String name,

        @Size(max = 500)
        String description,

        @Size(max = 80)
        String method,

        @NotEmpty(message = "Au moins une ligne de ventilation requise")
        List<@Valid Line> lines
) {
    @Schema(description = "Ligne de ventilation : un centre de coût et son poids relatif")
    public record Line(
            @Pattern(regexp = "^[A-Z0-9-]{2,12}$",
                    message = "Centre de coût : 2 à 12 caractères majuscules, chiffres ou tiret")
            String costCenter,

            @NotNull(message = "Poids requis")
            @DecimalMin(value = "0", inclusive = false, message = "Poids > 0 requis")
            BigDecimal weight
    ) {}
}
