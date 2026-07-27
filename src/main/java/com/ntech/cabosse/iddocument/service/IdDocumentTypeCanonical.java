package com.ntech.cabosse.iddocument.service;

import com.ntech.cabosse.shared.imports.FuzzyLabels;

import java.util.List;
import java.util.Map;

/**
 * Libellés canoniques des types de pièce d'identité courants.
 *
 * <p>Sert au rapprochement des fichiers importés : « CNI », « C.N.I. »,
 * « carte nationnale d'identite » et « Carte Nationale d'Identité » doivent
 * aboutir au même type, sinon le référentiel du tenant se remplit de
 * variantes et le filtrage devient inutilisable.</p>
 *
 * <p>Cette liste ne restreint pas la saisie : un type inconnu est créé tel
 * quel, simplement nettoyé. Elle ne fait que rattraper les formulations les
 * plus répandues.</p>
 */
public final class IdDocumentTypeCanonical {

    private IdDocumentTypeCanonical() {}

    /**
     * Libellé canonique et ses variantes rencontrées sur le terrain. Liste
     * ordonnée, pas une table : le parcours doit être déterministe, sinon
     * deux exécutions rattachent le même libellé à deux types différents.
     */
    private static final List<Map.Entry<String, List<String>>> ALIASES = List.of(
            Map.entry(
            "Carte nationale d'identité",
                    List.of("cni", "c n i", "carte nationale d identite", "carte d identite",
                            "carte identite", "carte nationale", "national id")),
            Map.entry("Passeport", List.of("passeport", "passport")),
            Map.entry("Carte consulaire", List.of("carte consulaire", "consulaire")),
            Map.entry("Attestation d'identité",
                    List.of("attestation d identite", "attestation identite", "attestation")),
            Map.entry("Permis de conduire", List.of("permis de conduire", "permis")),
            Map.entry("Carte d'électeur",
                    List.of("carte d electeur", "carte electeur", "carte electorale")),
            Map.entry("Identifiant national", List.of("nni", "numero national d identification",
                            "identifiant national", "numero nni")),
            Map.entry("Extrait de naissance",
                    List.of("extrait de naissance", "acte de naissance", "extrait naissance"))
    );

    /**
     * Ramène un libellé libre à sa forme canonique.
     *
     * @return le libellé canonique, ou le libellé nettoyé si aucun ne
     *         correspond : on ne perd jamais l'information saisie
     */
    public static String resolve(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String canonical = FuzzyLabels.canonical(raw);
        // Correspondance exacte d'abord : une abréviation connue ne doit
        // jamais être arbitrée par une distance d'édition.
        for (Map.Entry<String, List<String>> entry : ALIASES) {
            for (String alias : entry.getValue()) {
                if (canonical.equals(FuzzyLabels.canonical(alias))) return entry.getKey();
            }
        }
        for (Map.Entry<String, List<String>> entry : ALIASES) {
            for (String alias : entry.getValue()) {
                if (FuzzyLabels.matches(canonical, alias)) return entry.getKey();
            }
        }
        return raw.trim();
    }
}
