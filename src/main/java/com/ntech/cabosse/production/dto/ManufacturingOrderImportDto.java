package com.ntech.cabosse.production.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Payload d'import d'un ordre de fabrication depuis un fichier Excel
 * parsé côté client. L'OF est créé en {@code DRAFT} avec une liste de
 * consommations matière fournie directement — pas de recette préalable
 * nécessaire (voir {@code ManufacturingOrderService.createFromImport}).
 *
 * <p>Le produit fini et chaque article consommé peuvent soit référencer
 * un id existant, soit déclarer un {@code newArticle} qui sera créé à
 * la volée (resolve-or-create par nom + type, case-insensitive).</p>
 *
 * <p>Les KPIs réels ({@code producedQty}, {@code actualDurationHours},
 * {@code operatorsCount}) ne sont <em>pas</em> appliqués à la création
 * (un DRAFT n'a pas encore produit). Pour les enregistrer, transitionner
 * ensuite l'OF via {@code start} puis {@code complete} qui les accepte
 * comme paramètres.</p>
 */
@Schema(description = "Payload d'import d'un ordre de fabrication")
public record ManufacturingOrderImportDto(

        @NotNull(message = "Site requis")
        UUID siteId,

        /** PF existant ou à créer (resolve-or-create par nom + type). */
        @Valid
        @NotNull(message = "Produit fini requis")
        ImportedFinishedProduct finishedProduct,

        @NotNull(message = "Quantité planifiée requise")
        @DecimalMin(value = "0", inclusive = false, message = "Quantité > 0 requise")
        BigDecimal plannedQty,

        LocalDate scheduledDate,

        @Size(max = 40)
        String lotRef,

        @Size(max = 2000)
        String notes,

        @NotEmpty(message = "Au moins une ligne de consommation requise")
        List<@Valid ImportedConsumption> consumptions,

        /**
         * Si {@code true}, l'auto-création de produit fini ou d'article
         * consommé est désactivée — un référentiel inconnu fait échouer
         * l'OF avec un message explicite. Sert à éviter les doublons
         * d'orthographe sur les imports massifs (« lait » vs « lait cru »).
         */
        Boolean strictMode

) {

    /** Une ligne de consommation matière à l'import. */
    @Schema(description = "Ligne de consommation matière")
    public record ImportedConsumption(

            /** Article existant. Null si {@code newArticle} renseigné. */
            UUID articleId,

            @Valid
            ImportedArticle newArticle,

            @NotNull(message = "Quantité requise")
            @DecimalMin(value = "0", inclusive = false, message = "Quantité > 0 requise")
            BigDecimal quantity

    ) {}

    /** Article à créer durant l'import (matière ou consommable). */
    @Schema(description = "Article à créer durant l'import")
    public record ImportedArticle(

            @NotBlank(message = "Nom de l'article requis")
            @Size(min = 2, max = 120)
            String name,

            @Size(max = 40)
            String code,

            /** {@code RAW_MATERIAL} | {@code CONSUMABLE} | {@code PACKAGING}. */
            @NotBlank(message = "Type de l'article requis")
            String type,

            @NotBlank(message = "Unité requise")
            @Size(max = 20)
            String unit

    ) {}

    /** Produit fini de l'OF — existant ou à créer. */
    @Schema(description = "Produit fini cible de l'OF")
    public record ImportedFinishedProduct(

            /** PF existant. Null si à créer. */
            UUID id,

            @Size(min = 2, max = 120)
            String name,

            @Size(max = 40)
            String code,

            @Size(max = 20)
            String unit,

            /** Poids unitaire en grammes (pour KPI rendement matière). */
            Integer unitWeightGrams

    ) {}
}
