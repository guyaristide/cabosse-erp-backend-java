package com.ntech.cabosse.recipe.dto;

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
import java.util.UUID;

@Schema(description = "Payload d'écriture recette")
public record RecipeUpsertDto(
        @Pattern(regexp = "^$|^[a-z0-9-]{2,40}$")
        String code,

        @NotBlank
        @Size(min = 2, max = 120)
        String name,

        @Size(max = 1000)
        String description,

        @NotNull(message = "Produit fini requis")
        UUID finishedProductId,

        @NotNull
        @DecimalMin(value = "0.0001", inclusive = true, message = "Rendement strictement positif requis")
        BigDecimal yieldQty,

        @NotBlank
        @Size(max = 20)
        String yieldUnit,

        @NotEmpty(message = "Au moins un ingrédient requis")
        List<@Valid RecipeIngredientDto> ingredients,

        /**
         * Étapes de production optionnelles, dans l'ordre d'exécution.
         * Peut être null ou vide → l'OF s'exécutera en mono-statut.
         */
        List<@Valid RecipeStepDto> steps
) {}
