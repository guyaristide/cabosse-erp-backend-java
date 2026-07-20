package com.ntech.cabosse.analytics.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Une ligne de l'état budgétaire par programme/projet (backlog CPT-10) :
 * charges (classe 6) et produits (classe 7) imputés sur la période. Le
 * code {@code null} regroupe les écritures non affectées.
 */
@Schema(description = "Charges et produits d'un programme/projet sur une période")
public record ProgramReportRowDto(
        String program,
        String programName,
        String project,
        BigDecimal chargesFcfa,
        BigDecimal produitsFcfa
) {}
