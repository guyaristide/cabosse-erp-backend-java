package com.ntech.cabosse.catalog.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Activité — vue admin (avec activation)")
public record IndustryAdminDto(
        String code,
        String label,
        String description,
        boolean isActive
) {}
