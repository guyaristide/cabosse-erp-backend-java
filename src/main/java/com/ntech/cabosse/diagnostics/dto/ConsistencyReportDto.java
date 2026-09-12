package com.ntech.cabosse.diagnostics.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** L'ensemble des contrôles passés sur un tenant. */
@Schema(description = "Rapport de cohérence d'un tenant")
public record ConsistencyReportDto(
        String tenantName,
        Instant checkedAt,
        List<ConsistencyCheckDto> checks
) {
}
