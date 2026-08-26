package com.ntech.cabosse.site.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Payload de création / mise à jour d'un site.
 *
 * <p>{@code type} et {@code code} sont obligatoires à la création mais
 * ignorés à la mise à jour (immutables — un site ne change pas de type
 * et son code sert de FK depuis le stock / les achats / les ventes).</p>
 */
@Schema(description = "Payload d'écriture d'un site")
public record SiteUpsertDto(

        @Pattern(regexp = "^$|^(TRANSFORMATION|SALES_POINT|SECTION_WAREHOUSE|CENTRAL_WAREHOUSE)$",
                message = "{v.type-autorise-transformation-sales-point}"
                        + "| SECTION_WAREHOUSE | CENTRAL_WAREHOUSE")
        String type,

        @Pattern(regexp = "^$|^[A-Za-z0-9-]{2,40}$",
                message = "{v.code-en-lettres-chiffres-tirets-2-a-40-caracteres}")
        String code,

        @NotBlank(message = "{v.nom-requis}")
        @Size(min = 2, max = 120, message = "{v.nom-entre-2-et-120-caracteres}")
        String name,

        @Size(max = 250, message = "{v.adresse-trop-longue}")
        String addressLine,

        String cityId,
        String cityName,
        String regionCode,
        String countryCode,

        @DecimalMin(value = "-90", message = "{v.latitude-entre-90-et-90}")
        @DecimalMax(value = "90", message = "{v.latitude-entre-90-et-90}")
        Double latitude,

        @DecimalMin(value = "-180", message = "{v.longitude-entre-180-et-180}")
        @DecimalMax(value = "180", message = "{v.longitude-entre-180-et-180}")
        Double longitude,

        @Size(max = 25, message = "{v.telephone-trop-long}")
        @Pattern(regexp = "^$|^\\+?[\\d\\s()-]{6,25}$",
                message = "{v.numero-de-telephone-invalide}")
        String phone,

        @Size(max = 120, message = "{v.adresse-e-mail-trop-longue}")
        @Pattern(regexp = "^$|^.+@.+\\..+$", message = "{v.adresse-e-mail-invalide}")
        String email,

        @Size(max = 120, message = "{v.nom-du-responsable-trop-long}")
        String managerName,

        @Size(max = 1000, message = "{v.description-trop-longue-1000-caracteres-max}")
        String description,

        @Size(max = 250, message = "{v.horaires-trop-longs}")
        String openingHours

) {}
