package com.ntech.cabosse.expensetype.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Ligne d'import type de dépense (parsing client)")
public record ExpenseTypeImportRowDto(
        int rowNumber,
        String code,
        String name,
        String category,
        String syscohadaAccount,
        String description
) {}
