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
@Schema(description = "Un engagement qui attend son décaissement")
public record PayableDto(
        @Schema(description = "Nature de l'engagement, en code") String kind,
        @Schema(description = "Identifiant de l'opération d'origine") UUID sourceId,
        @Schema(description = "Ligne concernée, pour une réception à plusieurs fournisseurs")
        UUID lineId,
        @Schema(description = "Référence affichable de l'opération") String sourceRef,
        @Schema(description = "Nature du bénéficiaire, en code") String beneficiaryKind,
        UUID beneficiaryId,
        String beneficiaryName,
        @Schema(description = "Reste à payer") BigDecimal amount,
        @Schema(description = "Date de l'engagement, qui donne son ancienneté")
        LocalDate since,
        @Schema(description = "Ancienneté en jours, calculée sur l'horloge du serveur")
        long ageDays,
        UUID siteId,
        UUID campaignId
) {}
