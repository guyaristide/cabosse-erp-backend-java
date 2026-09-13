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
        UUID campaignId,
        @Schema(description = "Transmission à l'exécution par le comptable, si elle a eu lieu")
        java.time.Instant executionRequestedAt,
        /**
         * L'état de la demande de règlement en cours sur ce bénéficiaire,
         * s'il y en a une, et son identifiant.
         *
         * <p>La ligne le porte elle-même depuis le 13/09/2026. L'écran
         * interrogeait auparavant les demandes à part, avec un droit de
         * lecture que la caisse ne porte pas toujours : l'appel échouait
         * en silence et la ligne réaffichait « demander l'approbation »
         * alors qu'une demande existait, déjà approuvée. Le second dépôt
         * était alors refusé sans que rien n'ait prévenu.</p>
         */
        @Schema(description = "État de la demande de règlement en cours, en code")
        String settlementRequestStatus,
        UUID settlementRequestId
) {}
