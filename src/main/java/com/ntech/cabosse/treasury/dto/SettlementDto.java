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
@Schema(description = "Un règlement exécuté, avec sa trace")
public record SettlementDto(
        @Schema(description = "Nature du règlement, en code") String kind,
        @Schema(description = "Identifiant de l'opération d'origine") UUID sourceId,
        @Schema(description = "Référence de la demande réglée") String sourceRef,
        @Schema(description = "Nature du bénéficiaire, en code") String beneficiaryKind,
        UUID beneficiaryId,
        String beneficiaryName,
        @Schema(description = "Date du règlement : celle du chèque ou de la pièce de caisse")
        LocalDate settledAt,
        @Schema(description = "Montant remis au bénéficiaire, hors frais")
        BigDecimal amount,
        /**
         * Frais bancaires, distincts du montant remis.
         *
         * <p>Les fondre ferait croire que le bénéficiaire a reçu moins
         * qu'il n'a reçu. Ils sont à la charge de la structure, comme
         * en comptabilité.</p>
         */
        BigDecimal bankFees,
        @Schema(description = "Moyen de paiement, en code") String paymentMethod,
        /**
         * Référence du règlement telle qu'elle a été saisie : un numéro
         * de chèque se recopie, il ne se reformate pas.
         */
        String paymentRef,
        @Schema(description = "Compte de trésorerie mouvementé") UUID bankAccountId,
        @Schema(description = "Pièce comptable du règlement") String pieceRef,
        @Schema(description = "Nom de qui a exécuté le règlement") String settledByName,
        @Schema(description = "Adresse de qui a exécuté, quand le nom manque")
        String settledByEmail,
        UUID campaignId
) {}
