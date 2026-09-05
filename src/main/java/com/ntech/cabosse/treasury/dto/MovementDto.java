package com.ntech.cabosse.treasury.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/*
 * Extrait de son fichier-conteneur le 04/09/2026 : un fichier .java ne
 * porte qu'un seul type, règle de la maison rappelée par l'utilisateur.
 * Le propos d'ensemble du domaine vit dans le javadoc du service.
 */
@Schema(description = "Un mouvement du compte, et l'opération qui l'a produit")
public record MovementDto(
        LocalDate date,
        @Schema(description = "Référence de la pièce comptable") String pieceRef,
        String libelle,
        @Schema(description = "IN pour une entrée, OUT pour une sortie") String direction,
        BigDecimal amount,
        @Schema(description = "Solde du compte après ce mouvement") BigDecimal balance,
        @Schema(description = "Nature de l'opération d'origine, en code") String sourceType,
        @Schema(description = "Identifiant de l'opération, pour y renvoyer") UUID sourceId,
        String sourceRef,
        UUID campaignId
) {}
