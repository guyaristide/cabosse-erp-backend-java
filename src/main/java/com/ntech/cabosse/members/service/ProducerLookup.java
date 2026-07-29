package com.ntech.cabosse.members.service;

import com.ntech.cabosse.members.entity.MemberEntity;
import com.ntech.cabosse.members.entity.MemberIdentityDocument;
import com.ntech.cabosse.members.repository.MemberRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Retrouve un producteur à partir des numéros portés par un fichier importé.
 *
 * <p>Deux espaces distincts, jamais confondus : le <strong>code interne</strong>
 * attribué par la structure, et les <strong>numéros de carte</strong> issus
 * des pièces dont le type sert d'identifiant. Les mélanger dans un même
 * dictionnaire, comme le faisaient les trois imports, laissait le code
 * interne d'un producteur l'emporter sur la carte d'un autre, sans que rien
 * ne le signale.</p>
 *
 * <p>Une seule colonne renseignée cherche dans les deux espaces, parce que
 * les fichiers de terrain n'en portent souvent qu'une. Deux colonnes qui
 * désignent deux producteurs différents sont une contradiction, pas une
 * priorité à arbitrer.</p>
 */
@ApplicationScoped
public class ProducerLookup {

    @Inject MemberRepository members;

    /** Index construit une fois par import, pas une requête par ligne. */
    public Index index() {
        Map<String, MemberEntity> byInternal = new HashMap<>();
        Map<String, MemberEntity> byCard = new HashMap<>();
        Map<String, List<MemberEntity>> cardCollisions = new HashMap<>();

        for (MemberEntity m : members.listAll()) {
            if (m.code != null && !m.code.isBlank()) {
                byInternal.putIfAbsent(m.code.trim().toUpperCase(Locale.ROOT), m);
            }
            if (m.producerRefKeys == null) continue;
            for (String key : m.producerRefKeys) {
                MemberEntity previous = byCard.putIfAbsent(key, m);
                if (previous != null && !previous.id.equals(m.id)) {
                    cardCollisions.computeIfAbsent(key, k -> new java.util.ArrayList<>(List.of(previous)))
                            .add(m);
                }
            }
        }
        return new Index(byInternal, byCard, cardCollisions);
    }

    /** Résultat d'un rapprochement : le producteur, ou la raison de l'échec. */
    public record Match(MemberEntity member, String failure) {
        public boolean found() { return member != null; }
    }

    public static final class Index {
        private final Map<String, MemberEntity> byInternal;
        private final Map<String, MemberEntity> byCard;
        private final Map<String, List<MemberEntity>> cardCollisions;

        private Index(Map<String, MemberEntity> byInternal, Map<String, MemberEntity> byCard,
                      Map<String, List<MemberEntity>> cardCollisions) {
            this.byInternal = byInternal;
            this.byCard = byCard;
            this.cardCollisions = cardCollisions;
        }

        /**
         * @param internalRef code interne du producteur, tel que porté par le fichier
         * @param cardRef     numéro de carte, tel que porté par le fichier
         */
        public Match resolve(String internalRef, String cardRef) {
            MemberEntity byCode = internalRef == null || internalRef.isBlank()
                    ? null : byInternal.get(internalRef.trim().toUpperCase(Locale.ROOT));

            String cardKey = MemberIdentityDocument.normalize(cardRef);
            if (cardKey != null && cardCollisions.containsKey(cardKey)) {
                return new Match(null, "Le numéro « " + cardRef.trim()
                        + " » est porté par plusieurs producteurs. Corrigez les fiches avant d'importer.");
            }
            MemberEntity byCardNumber = cardKey == null ? null : byCard.get(cardKey);

            if (byCode != null && byCardNumber != null && !byCode.id.equals(byCardNumber.id)) {
                return new Match(null, "Le n° interne désigne « " + byCode.name
                        + " » et le n° de carte « " + byCardNumber.name + " ».");
            }
            MemberEntity found = byCode != null ? byCode : byCardNumber;

            // Colonne unique : le fichier ne dit pas de quelle nature est le
            // numéro, on cherche donc dans les deux espaces.
            if (found == null && (internalRef != null && !internalRef.isBlank())) {
                String asCard = MemberIdentityDocument.normalize(internalRef);
                if (asCard != null && cardCollisions.containsKey(asCard)) {
                    return new Match(null, "Le numéro « " + internalRef.trim()
                            + " » est porté par plusieurs producteurs.");
                }
                found = asCard == null ? null : byCard.get(asCard);
            }
            if (found == null && (cardRef != null && !cardRef.isBlank())) {
                found = byInternal.get(cardRef.trim().toUpperCase(Locale.ROOT));
            }
            if (found == null) {
                return new Match(null, "Producteur introuvable (n° interne ou n° de carte).");
            }
            return new Match(found, null);
        }
    }
}
