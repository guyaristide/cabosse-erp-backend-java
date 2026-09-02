package com.ntech.cabosse.treasury.dto;

import com.ntech.cabosse.shared.api.Pagination;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Ce que la structure a réellement sorti, et par quelle main.
 *
 * <p>La file « à payer » montre ce qui attend et fait disparaître la ligne
 * une fois payée : elle ne dit jamais ce qui a été fait, ni par qui. Or
 * l'état attendu par la caisse est un tableau de suivi, pas seulement une
 * file de travail. Le relevé de compte en montre déjà une partie,
 * mouvement par mouvement ; il manquait la lecture par bénéficiaire et par
 * demande.</p>
 *
 * <p>Rien de nouveau n'est stocké : le décaissement conservait déjà le
 * moyen, le compte, la référence, les frais, l'horodatage et l'exécutant.
 * Ce qui manquait était de le donner à lire.</p>
 */
public final class SettlementDtos {

    private SettlementDtos() {}

    /** Ce que le règlement a soldé. */
    public enum SettlementKind {
        /** Avance décaissée à un délégué collecteur. */
        COLLECTOR_ADVANCE,
        /** Crédit décaissé à un producteur membre. */
        MEMBER_CREDIT,
        /** Règlement de livraisons à un producteur ou à un délégué. */
        PRODUCER_PAYMENT
    }

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
            BigDecimal amountFcfa,
            /**
             * Frais bancaires, distincts du montant remis.
             *
             * <p>Les fondre ferait croire que le bénéficiaire a reçu moins
             * qu'il n'a reçu. Ils sont à la charge de la structure, comme
             * en comptabilité.</p>
             */
            BigDecimal bankFeesFcfa,
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

    /**
     * L'état et ce qu'il pèse.
     *
     * <p>Les totaux portent sur toute la période après filtrage, jamais
     * sur la page : lire un total en bas d'une première page sur dix
     * donnerait un chiffre faux à qui rend compte.</p>
     */
    @Schema(description = "État des règlements exécutés sur une période")
    public record SettlementReportDto(
            LocalDate from,
            LocalDate to,
            BigDecimal totalAmountFcfa,
            BigDecimal totalBankFeesFcfa,
            int beneficiaryCount,
            Pagination<SettlementDto> page
    ) {}
}
