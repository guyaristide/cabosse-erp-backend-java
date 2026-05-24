package com.ntech.cabosse.catalog.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Activité / filière — catalogue strict éditable par la plateforme")
public record IndustryResponseDto(
        String code,
        String label,
        String description
) {}
