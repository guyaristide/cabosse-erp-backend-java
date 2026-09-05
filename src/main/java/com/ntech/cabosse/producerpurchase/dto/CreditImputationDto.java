package com.ntech.cabosse.producerpurchase.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;

/*
 * Extrait de son fichier-conteneur le 04/09/2026 : un fichier .java ne
 * porte qu'un seul type, règle de la maison rappelée par l'utilisateur.
 */
/**
 * Retenue décidée sur cette livraison au titre d'un crédit ou d'une
 * avance du producteur. Rien n'est retenu d'office : c'est une personne
 * qui fixe, engagement par engagement, ce qu'elle prélève.
 */
@Schema(description = "Retenue décidée au titre d'un crédit ou d'une avance")
public record CreditImputationDto(
        @NotNull(message = "{v.engagement-requis}") java.util.UUID creditId,
        @NotNull(message = "{v.montant-requis}")
        @DecimalMin(value = "0", inclusive = false, message = "{v.montant-0-requis}")
        BigDecimal amount,
        @Size(max = 500) String notes
) {}
