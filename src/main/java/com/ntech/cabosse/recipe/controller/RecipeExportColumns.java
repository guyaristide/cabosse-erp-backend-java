package com.ntech.cabosse.recipe.controller;

import com.ntech.cabosse.recipe.dto.RecipeResponseDto;
import com.ntech.cabosse.shared.export.ExportColumn;

import java.util.List;

final class RecipeExportColumns {

    private RecipeExportColumns() {}

    static List<ExportColumn<RecipeResponseDto>> all() {
        return List.of(
                ExportColumn.of("Code",              RecipeResponseDto::code),
                ExportColumn.of("Nom",               RecipeResponseDto::name),
                ExportColumn.of("Produit fini",      RecipeResponseDto::finishedProductName),
                ExportColumn.of("Rendement",         RecipeResponseDto::yieldQty),
                ExportColumn.of("Unité",             RecipeResponseDto::yieldUnit),
                ExportColumn.of("Nombre ingrédients", r -> r.ingredients() == null ? 0 : r.ingredients().size()),
                ExportColumn.of("Actif",             RecipeResponseDto::active),
                ExportColumn.of("Description",       RecipeResponseDto::description)
        );
    }
}
