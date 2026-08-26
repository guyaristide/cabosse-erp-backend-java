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
                message = "Type autorisé : TRANSFORMATION | SALES_POINT "
                        + "| SECTION_WAREHOUSE | CENTRAL_WAREHOUSE")
        String type,

        @Pattern(regexp = "^$|^[A-Za-z0-9-]{2,40}$",
                message = "Code en lettres / chiffres / tirets, 2 à 40 caractères")
        String code,

        @NotBlank(message = "Nom requis")
        @Size(min = 2, max = 120, message = "Nom entre 2 et 120 caractères")
        String name,

        @Size(max = 250, message = "Adresse trop longue")
        String addressLine,

        String cityId,
        String cityName,
        String regionCode,
        String countryCode,

        @DecimalMin(value = "-90", message = "Latitude entre -90 et 90")
        @DecimalMax(value = "90", message = "Latitude entre -90 et 90")
        Double latitude,

        @DecimalMin(value = "-180", message = "Longitude entre -180 et 180")
        @DecimalMax(value = "180", message = "Longitude entre -180 et 180")
        Double longitude,

        @Size(max = 25, message = "Téléphone trop long")
        @Pattern(regexp = "^$|^\\+?[\\d\\s()-]{6,25}$",
                message = "Numéro de téléphone invalide")
        String phone,

        @Size(max = 120, message = "Adresse e-mail trop longue")
        @Pattern(regexp = "^$|^.+@.+\\..+$", message = "Adresse e-mail invalide")
        String email,

        @Size(max = 120, message = "Nom du responsable trop long")
        String managerName,

        @Size(max = 1000, message = "Description trop longue (1000 caractères max)")
        String description,

        @Size(max = 250, message = "Horaires trop longs")
        String openingHours

) {}
