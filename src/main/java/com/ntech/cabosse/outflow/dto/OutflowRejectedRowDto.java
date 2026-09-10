package com.ntech.cabosse.outflow.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Une ligne refusée et la raison, pour corriger le fichier. */
@Schema(description = "Ligne d'import refusée")
public record OutflowRejectedRowDto(int rowNumber, String reason) {}
