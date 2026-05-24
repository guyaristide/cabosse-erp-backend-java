package com.ntech.cabosse.recipe.dto;

import com.ntech.cabosse.recipe.entity.RecipeEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Recette / nomenclature du tenant")
public record RecipeResponseDto(
        UUID id, String code, String name, String description,
        UUID finishedProductId, String finishedProductName,
        BigDecimal yieldQty, String yieldUnit,
        List<RecipeIngredientDto> ingredients,
        List<RecipeStepResponseDto> steps,
        boolean active, Instant createdAt, Instant updatedAt
) {
    public static RecipeResponseDto from(RecipeEntity e) {
        List<RecipeIngredientDto> lines = e.ingredients == null
                ? List.of()
                : e.ingredients.stream()
                        .map(i -> new RecipeIngredientDto(i.articleId, i.articleName, i.quantity, i.unit))
                        .toList();
        List<RecipeStepResponseDto> stepDtos = e.steps == null
                ? List.of()
                : e.steps.stream()
                        .map(s -> new RecipeStepResponseDto(
                                s.order, s.name, s.description, s.expectedDurationMinutes))
                        .toList();
        return new RecipeResponseDto(
                e.id, e.code, e.name, e.description,
                e.finishedProductId, e.finishedProductName,
                e.yieldQty, e.yieldUnit,
                lines,
                stepDtos,
                e.active, e.createdAt, e.updatedAt
        );
    }
}
