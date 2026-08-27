package com.ntech.cabosse.recipe.controller;

import com.ntech.cabosse.recipe.dto.RecipeResponseDto;
import com.ntech.cabosse.shared.export.ColumnKind;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;

import java.util.List;

final class RecipeExportColumns {

    private RecipeExportColumns() {}

    static List<ExportColumn<RecipeResponseDto>> all() {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-code"),              RecipeResponseDto::code),
                ExportColumn.of(Messages.msg("m.imp-h-member-last-name"),               RecipeResponseDto::name),
                ExportColumn.of(Messages.msg("m.imp-h-produit-fini"),      RecipeResponseDto::finishedProductName),
                ExportColumn.of("rendement", Messages.msg("m.imp-h-rendement"), ColumnKind.NUMBER_QTY,         RecipeResponseDto::yieldQty),
                ExportColumn.of(Messages.msg("m.imp-h-article-unit"),             RecipeResponseDto::yieldUnit),
                ExportColumn.of(Messages.msg("m.imp-h-nombre-ingredients"), r -> r.ingredients() == null ? 0 : r.ingredients().size()),
                ExportColumn.of(Messages.msg("m.imp-h-actif"),             RecipeResponseDto::active),
                ExportColumn.of(Messages.msg("m.imp-h-description"),       RecipeResponseDto::description)
        );
    }
}
