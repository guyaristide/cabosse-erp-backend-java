package com.ntech.cabosse.members.repository;

import com.ntech.cabosse.members.entity.MemberGender;
import com.ntech.cabosse.members.entity.MemberStatus;

import java.util.UUID;

/**
 * Ce sur quoi on restreint une liste de producteurs (relevé le 27/09/2026 :
 * l'écran n'offrait qu'une recherche et le statut).
 *
 * <p>Un sociétariat de deux mille cinq cents fiches ne se parcourt pas :
 * il se restreint. Les critères retenus sont ceux que la liste affiche
 * déjà, parce que filtrer sur ce qu'on ne voit pas laisse sans moyen de
 * vérifier ce qu'on a obtenu.</p>
 *
 * <p>Un champ nul ne restreint rien. C'est ce qui permet de composer les
 * critères sans multiplier les requêtes.</p>
 */
public record MemberSearchCriteria(
        String q,
        MemberStatus status,
        /** Vrai : les délégués seuls. Faux : les autres. Nul : tous. */
        Boolean collector,
        UUID sectionId,
        String village,
        MemberGender gender,
        /** Vrai : les fiches sans genre seulement. Nul : sans effet. */
        Boolean genderMissing,
        /** Vrai : avec au moins une parcelle ; faux : sans ; nul : sans effet. */
        Boolean hasParcels,
        /** Vrai : les fiches converties en fournisseur seulement ; nul : jamais elles. */
        Boolean converted,
        /**
         * Vrai : une part sociale souscrite ; faux : aucune, nulle ou à zéro,
         * donc pas d'adhésion (demandé le 28/10/2026) ; nul : sans effet.
         */
        Boolean holdsShares) {

    public static MemberSearchCriteria none() {
        return new MemberSearchCriteria(null, null, null, null, null, null, null, null, null, null);
    }
}
