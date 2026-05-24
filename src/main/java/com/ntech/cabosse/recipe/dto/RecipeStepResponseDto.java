package com.ntech.cabosse.recipe.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Une étape de production retournée dans la réponse — inclut le champ
 * {@code order} calculé par le service (contrairement à
 * {@link RecipeStepDto} qui ne le contient pas côté entrée).
 */
@Schema(description = "Étape de production telle que persistée")
public record RecipeStepResponseDto(
        int order,
        String name,
        String description,
        Integer expectedDurationMinutes
) {}
