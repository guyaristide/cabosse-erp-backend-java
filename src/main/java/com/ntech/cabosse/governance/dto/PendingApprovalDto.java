package com.ntech.cabosse.governance.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/*
 * Extrait de son fichier-conteneur le 04/09/2026 : un fichier .java ne
 * porte qu'un seul type, règle de la maison rappelée par l'utilisateur.
 * Le propos d'ensemble du domaine vit dans le javadoc du service.
 */
@Schema(description = "Une demande qui attend sa décision")
public record PendingApprovalDto(
        @Schema(description = "Nature de la demande, en code") String kind,
        @Schema(description = "Identifiant de la demande d'origine") UUID sourceId,
        @Schema(description = "Référence affichable") String sourceRef,
        UUID beneficiaryId,
        String beneficiaryName,
        @Schema(description = "Montant sollicité") BigDecimal amount,
        @Schema(description = "Date de la demande, qui donne son ancienneté")
        LocalDate since,
        @Schema(description = "Ancienneté en jours, sur l'horloge du serveur")
        long ageDays,
        /**
         * Solde du compte courant du bénéficiaire, pour voir si on a
         * affaire à quelqu'un qui doit déjà. Positif : il doit à la
         * structure. Nul quand la notion n'existe pas pour cette
         * nature.
         */
        @Schema(description = "Solde du compte courant, positif quand le bénéficiaire doit")
        BigDecimal accountBalance,
        /** Contrepartie attendue, figée à la demande, et son unité. */
        BigDecimal expectedQuantity,
        String expectedQuantityUnit,
        @Schema(description = "Commentaire de l'émetteur de la demande")
        String requesterNote,
        @Schema(description = "Qui a déposé la demande") String requestedByEmail,
        /**
         * La demande attend-elle l'organe de gouvernance, ou la
         * direction seule ? Sans cela, personne ne sait qui relancer.
         */
        boolean governanceApprovalRequired,
        /**
         * L'utilisateur courant peut-il trancher cette ligne ? Faux sur
         * la file consultée, et faux quand la demande dépasse le seuil
         * sans que le profil porte le droit de gouvernance.
         */
        boolean actionable,
        UUID siteId,
        UUID campaignId
) {}
