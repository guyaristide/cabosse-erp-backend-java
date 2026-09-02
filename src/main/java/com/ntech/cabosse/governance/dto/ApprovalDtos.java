package com.ntech.cabosse.governance.dto;

import com.ntech.cabosse.shared.api.Pagination;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Ce qui attend une décision, rassemblé du point de vue de celui qui
 * tranche.
 *
 * <p>Celui qui approuve ne fait pas fonctionner la collecte. Le renvoyer
 * vers la liste opérationnelle l'oblige à filtrer lui-même pour retrouver
 * ce qui l'attend, au milieu de demandes déjà décaissées qui ne le
 * concernent plus. C'est le raisonnement des files de trésorerie : la même
 * donnée, rassemblée là où se prend la décision.</p>
 *
 * <p>Deux files, et elles ne se valent pas. Les avances aux délégués
 * s'approuvent depuis cet écran. Les crédits aux producteurs s'y
 * consultent seulement : la direction tranche seule, et y placer un bouton
 * d'approbation contredirait le circuit à deux mains.</p>
 *
 * <p>Les natures sont rendues en <b>codes</b>, jamais en libellés
 * traduits : un client qui comparerait des chaînes françaises casserait à
 * la première bascule de langue.</p>
 */
public final class ApprovalDtos {

    private ApprovalDtos() {}

    /** Ce qui attend, et de qui. */
    public enum ApprovalKind {
        /** Avance à un délégué collecteur. Se décide depuis cet écran. */
        COLLECTOR_ADVANCE,
        /** Crédit à un producteur membre. Consultation seule ici. */
        MEMBER_CREDIT
    }

    @Schema(description = "Une demande qui attend sa décision")
    public record PendingApprovalDto(
            @Schema(description = "Nature de la demande, en code") String kind,
            @Schema(description = "Identifiant de la demande d'origine") UUID sourceId,
            @Schema(description = "Référence affichable") String sourceRef,
            UUID beneficiaryId,
            String beneficiaryName,
            @Schema(description = "Montant sollicité") BigDecimal amountFcfa,
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
            BigDecimal accountBalanceFcfa,
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

    /**
     * La file et ce qu'elle pèse.
     *
     * <p>Le total porte sur tout ce qui attend après filtrage, jamais sur
     * la page affichée : un conseil qui lirait le total de la première
     * page sur cinq croirait connaître l'engagement soumis.</p>
     */
    @Schema(description = "File des demandes en attente de décision, et leur total")
    public record ApprovalQueueDto(
            BigDecimal totalPendingFcfa,
            int requestCount,
            /**
             * Ancienneté de la plus vieille demande. Un total seul ne dit
             * pas si le conseil a du retard : cinq millions déposés hier et
             * cinq millions qui attendent depuis six semaines n'appellent
             * pas la même réaction.
             */
            long oldestAgeDays,
            Pagination<PendingApprovalDto> page
    ) {}
}
