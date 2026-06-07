package com.ntech.cabosse.direction.dto;

/**
 * Alerte stratégique remontée à la direction.
 *
 * @param severity {@code info|warning|danger}
 */
public record ExecutiveAlertDto(
        String id,
        String severity,
        String title,
        String detail
) {}
