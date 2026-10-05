package com.ntech.cabosse.cashforecast.dto;

import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Le motif d'un refus.
 *
 * <p>Facultatif, mais il revient au directeur avec le prévisionnel :
 * sans lui, la correction attendue se devine.</p>
 */
@Schema(description = "Refus d'un prévisionnel de décaissement")
public record RejectForecastDto(
        @Size(max = 500, message = "{v.motif-trop-long}")
        String reason
) {}
