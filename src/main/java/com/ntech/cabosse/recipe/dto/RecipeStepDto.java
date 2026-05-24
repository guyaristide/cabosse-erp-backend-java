package com.ntech.cabosse.recipe.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Une étape de production saisie sur une recette. L'ordre est dérivé
 * de la position dans la liste — l'API ne lit pas le champ {@code order}
 * du client (re-numérotation côté service).
 */
@Schema(description = "Étape de production d'une recette (optionnelle)")
public record RecipeStepDto(

        @NotBlank(message = "Nom de l'étape requis")
        @Size(max = 100, message = "Nom trop long (100 caractères max)")
        String name,

        @Size(max = 500, message = "Description trop longue (500 caractères max)")
        String description,

        @Positive(message = "Durée doit être positive")
        Integer expectedDurationMinutes

) {}
