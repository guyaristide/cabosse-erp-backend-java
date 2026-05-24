package com.ntech.cabosse.catalog.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/**
 * Payload d'écriture plan tarifaire. Le code est dans body pour POST,
 * dans URL pour PUT (immutable).
 */
@Schema(description = "Payload d'écriture plan tarifaire")
public record PlanUpsertDto(

        @Pattern(regexp = "^$|^[a-z0-9-]{2,40}$",
                message = "Slug en minuscules / chiffres / tirets, 2 à 40 caractères")
        String code,

        @NotBlank(message = "Nom requis")
        @Size(min = 2, max = 80, message = "Nom entre 2 et 80 caractères")
        String name,

        @Size(max = 300, message = "Description trop longue")
        String description,

        @NotNull(message = "Prix mensuel requis (0 pour 'sur devis')")
        @Min(value = 0, message = "Prix mensuel négatif interdit")
        BigDecimal monthlyPriceFcfa,

        @NotNull(message = "Prix annuel requis (0 pour 'sur devis')")
        @Min(value = 0, message = "Prix annuel négatif interdit")
        BigDecimal yearlyPriceFcfa,

        @Min(value = 1, message = "Au moins 1 user")
        int maxUsers,

        @Min(value = 1, message = "Au moins 1 site")
        int maxSites,

        @NotNull(message = "Liste des modules requise (peut être vide)")
        List<String> includedModules,

        @NotNull(message = "Liste des features requise (peut être vide)")
        List<String> features,

        boolean active

) {}
