package com.ntech.cabosse.intake.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Correction d'un bordereau de réception encore à comptabiliser : le
 * magasinier rattrape une erreur de saisie de son carnet (poids, sacs,
 * fournisseur, date) avant que le comptable ne constate un faux écart.
 * Le numéro du bordereau, lui, est son identité : il ne se corrige pas,
 * on supprime et on réimporte.
 */
@Schema(description = "Correction d'un bordereau de réception à comptabiliser")
public record IntakeNoteCorrectionDto(
        @NotNull LocalDate date,
        /**
         * Magasin où la matière est entrée. Réglé au magasin, pas à la
         * comptabilisation : c'est le magasinier qui le sait.
         */
        java.util.UUID siteId,
        @Size(max = 160) String supplierName,
        @DecimalMin(value = "0", message = "{v.valeur-negative-interdite}") BigDecimal grossWeightKg,
        Integer bagCount,
        @NotNull @DecimalMin(value = "0.001", message = "{v.valeur-negative-interdite}")
        BigDecimal netWeightKg
) {}
