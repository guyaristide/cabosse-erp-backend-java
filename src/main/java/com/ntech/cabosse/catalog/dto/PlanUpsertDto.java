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
                message = "{v.slug-en-minuscules-chiffres-tirets-2-a-40-caracteres}")
        String code,

        @NotBlank(message = "{v.nom-requis}")
        @Size(min = 2, max = 80, message = "{v.nom-entre-2-et-80-caracteres}")
        String name,

        @Size(max = 300, message = "{v.description-trop-longue}")
        String description,

        @NotNull(message = "{v.prix-mensuel-requis-0-pour-sur-devis}")
        @Min(value = 0, message = "{v.prix-mensuel-negatif-interdit}")
        BigDecimal monthlyPrice,

        @NotNull(message = "{v.prix-annuel-requis-0-pour-sur-devis}")
        @Min(value = 0, message = "{v.prix-annuel-negatif-interdit}")
        BigDecimal yearlyPrice,

        @Min(value = 1, message = "{v.au-moins-1-user}")
        int maxUsers,
        /** Plafond de producteurs membres. Nul : non contraint. */
        int maxMembers,

        @Min(value = 1, message = "{v.au-moins-1-site}")
        int maxSites,

        @NotNull(message = "{v.liste-des-modules-requise-peut-etre-vide}")
        List<String> includedModules,

        @NotNull(message = "{v.liste-des-features-requise-peut-etre-vide}")
        List<String> features,

        boolean active

) {}
