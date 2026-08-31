package com.ntech.cabosse.treasury.dto;

import com.ntech.cabosse.shared.api.Pagination;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Ce que la structure doit sortir, tous engagements confondus.
 *
 * <p>La dette existe depuis toujours, mais éclatée : une avance approuvée
 * vit dans la collecte, un crédit approuvé chez les membres, une réception
 * non réglée dans les achats, un reçu producteur dans son propre suivi.
 * Personne ne pouvait répondre à « combien faut-il sortir cette semaine,
 * et à qui ». C'est cette réponse-là que la file rassemble.</p>
 *
 * <p>Aucun état nouveau n'est créé : chaque source porte déjà l'information
 * qui dit qu'elle attend un paiement. Le travail est de ramener quatre
 * formes différentes à une seule ligne.</p>
 *
 * <p>Les natures sont rendues en <b>codes</b>, jamais en libellés : un
 * serveur qui renverrait du français obligerait le client à comparer des
 * chaînes traduites, et la comparaison casserait à la première bascule de
 * langue.</p>
 */
public final class PayableDtos {

    private PayableDtos() {}

    /** D'où vient l'engagement. */
    public enum PayableKind {
        /** Avance approuvée à un délégué collecteur, en attente de décaissement. */
        COLLECTOR_ADVANCE,
        /** Crédit approuvé à un producteur membre, en attente de décaissement. */
        MEMBER_CREDIT,
        /** Ligne de réception fournisseur non réglée. */
        SUPPLIER_RECEIPT,
        /** Reste dû à un producteur ou à un délégué sur ses livraisons. */
        PRODUCER_PURCHASE
    }

    /** D'où vient l'encaissement attendu. */
    public enum ReceivableKind {
        /** Vente confirmée ou livrée, non soldée. */
        SALE
    }

    /** À qui la structure doit, ou qui lui doit. */
    public enum BeneficiaryKind {
        DELEGATE, MEMBER, SUPPLIER, CUSTOMER
    }

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
            @Schema(description = "Reste à payer") BigDecimal amountFcfa,
            @Schema(description = "Date de l'engagement, qui donne son ancienneté")
            LocalDate since,
            @Schema(description = "Ancienneté en jours, calculée sur l'horloge du serveur")
            long ageDays,
            UUID siteId,
            UUID campaignId
    ) {}

    /**
     * La file et son total.
     *
     * <p>Le total porte sur <b>tout</b> ce qui est dû après filtrage, pas
     * sur la page affichée : un caissier qui lit « 4 200 000 » en bas de la
     * première page sur dix croirait connaître son besoin de trésorerie.</p>
     */
    @Schema(description = "File des engagements à payer, et le total dû")
    public record PayableQueueDto(
            BigDecimal totalRemainingFcfa,
            int beneficiaryCount,
            /**
             * Ancienneté de la plus vieille ligne de la file, tous filtres
             * appliqués. Un total seul ne dit pas si la structure a du
             * retard : cinq millions dus depuis hier et cinq millions dus
             * depuis six semaines n'appellent pas la même décision.
             */
            long oldestAgeDays,
            Pagination<PayableDto> page
    ) {}
}
