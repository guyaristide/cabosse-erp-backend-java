package com.ntech.cabosse.members.service;

import com.ntech.cabosse.iddocument.entity.IdDocumentTypeEntity;
import com.ntech.cabosse.iddocument.repository.IdDocumentTypeRepository;
import com.ntech.cabosse.members.entity.MemberEntity;
import com.ntech.cabosse.members.entity.MemberIdentityDocument;
import com.ntech.cabosse.members.repository.MemberRepository;
import com.ntech.cabosse.shared.exception.ConflictException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Clés par lesquelles un producteur se retrouve dans un fichier importé.
 *
 * <p>Une clé vient d'une pièce dont le <em>type</em> est coché « sert
 * d'identifiant » dans le référentiel : la carte délivrée par un organisme
 * de filière en est une, un passeport n'en est pas une. Une structure dont
 * la filière ne délivre aucune carte n'aura aucune clé, et le rapprochement
 * se fera sur le seul code interne du membre.</p>
 *
 * <p>L'unicité est vérifiée ici plutôt que laissée à l'index seul, pour que
 * le refus nomme le producteur déjà porteur du numéro. Un doublon est
 * presque toujours une faute de saisie, et l'accepter en silence revient à
 * payer un achat à la mauvaise personne.</p>
 */
@ApplicationScoped
public class ProducerRefKeyService {

    @Inject IdDocumentTypeRepository types;
    @Inject MemberRepository members;

    /** Libellés des types dont le numéro sert à retrouver un producteur. */
    public Set<String> identifierTypeNames() {
        return typeNames(t -> t.usableAsProducerRef);
    }

    /**
     * Libellés des types qui établissent l'identité de la personne.
     *
     * @return {@code null} quand le référentiel est vide : aucun type n'a
     *         encore été déclaré, toute pièce compte alors, pour ne pas
     *         faire régresser la complétude des dossiers déjà saisis. Un
     *         ensemble vide, lui, signifie qu'aucun type déclaré ne prouve
     *         l'identité, et la règle s'applique strictement.
     */
    public Set<String> identityProofTypeNames() {
        if (types.listAll().isEmpty()) return null;
        return typeNames(t -> t.identityProof);
    }

    private Set<String> typeNames(java.util.function.Predicate<IdDocumentTypeEntity> filter) {
        Set<String> names = new LinkedHashSet<>();
        for (IdDocumentTypeEntity t : types.listAll()) {
            if (t.name != null && !t.name.isBlank() && filter.test(t)) {
                names.add(t.name.trim().toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    /** Clés portées par ces pièces, sans doublon, dans l'ordre de saisie. */
    public List<String> keysOf(List<MemberIdentityDocument> documents) {
        Set<String> identifiers = identifierTypeNames();
        Set<String> keys = new LinkedHashSet<>();
        if (documents == null) return new ArrayList<>();
        for (MemberIdentityDocument d : documents) {
            if (d == null || d.type == null) continue;
            if (!identifiers.contains(d.type.trim().toLowerCase(Locale.ROOT))) continue;
            String key = MemberIdentityDocument.normalize(d.number);
            if (key != null) keys.add(key);
        }
        return new ArrayList<>(keys);
    }

    /**
     * Refuse un numéro déjà porté par un autre producteur, en le nommant.
     *
     * @param memberId membre en cours d'écriture, exclu du contrôle
     */
    public void ensureAvailable(List<String> keys, UUID memberId) {
        if (keys == null || keys.isEmpty()) return;
        for (String key : keys) {
            for (MemberEntity other : members.findByProducerRefKey(key)) {
                if (memberId != null && memberId.equals(other.id)) continue;
                throw new ConflictException(
                        "Le numéro « " + key + " » est déjà porté par « " + other.name
                                + " ». Un même numéro ne peut pas désigner deux producteurs.");
            }
        }
    }

    /**
     * Recalcule les clés des membres concernés après un changement sur un
     * type : cocher « sert d'identifiant » doit rendre les numéros existants
     * retrouvables, le décocher doit cesser de les exposer.
     */
    public void resyncForType(String previousName, String newName) {
        Set<String> touched = new LinkedHashSet<>();
        if (previousName != null && !previousName.isBlank()) touched.add(previousName.trim());
        if (newName != null && !newName.isBlank()) touched.add(newName.trim());
        for (String name : touched) {
            for (MemberEntity m : members.findByDocumentType(name)) {
                if (previousName != null && !previousName.equals(newName)
                        && m.identityDocuments != null) {
                    // Un type renommé emporte les pièces qui le référencent.
                    m.identityDocuments.stream()
                            .filter(d -> d != null && previousName.equalsIgnoreCase(d.type))
                            .forEach(d -> d.type = newName);
                }
                m.producerRefKeys = keysOf(m.identityDocuments);
                members.updateProducerRefKeys(m);
            }
        }
    }
}
