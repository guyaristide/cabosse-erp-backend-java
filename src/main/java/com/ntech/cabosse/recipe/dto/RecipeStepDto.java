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

        @NotBlank(message = "{v.nom-de-l-etape-requis}")
        @Size(max = 100, message = "{v.nom-trop-long-100-caracteres-max}")
        String name,

        @Size(max = 500, message = "{v.description-trop-longue-500-caracteres-max}")
        String description,

        @Positive(message = "{v.duree-doit-etre-positive}")
        Integer expectedDurationMinutes

) {}
