package com.ntech.cabosse.diagnostics.dto;

/** Une ligne du rapport de cohérence, mise à plat pour l'export. */
public record ConsistencyRowDto(String check, long anomalies, String detail) {
}
