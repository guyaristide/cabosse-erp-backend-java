package com.ntech.cabosse.recipe.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/** Une ligne BOM en payload / réponse. */
@Schema(description = "Ligne de nomenclature (BOM)")
public record RecipeIngredientDto(

        @NotNull(message = "{v.article-requis}")
        UUID articleId,

        /** Dénormalisé — peut être omis en POST, le serveur le résout. */
        String articleName,

        @NotNull
        @DecimalMin(value = "0.0001", inclusive = true, message = "{v.quantite-strictement-positive-requise}")
        BigDecimal quantity,

        @NotBlank(message = "{v.unite-requise}")
        @Size(max = 20)
        String unit
) {}
