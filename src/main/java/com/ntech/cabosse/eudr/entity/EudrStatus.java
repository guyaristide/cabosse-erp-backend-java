package com.ntech.cabosse.eudr.entity;

/**
 * État de conformité EUDR d'une parcelle (règlement UE 2023/1115).
 *
 * <ul>
 *   <li>{@link #NOT_STARTED} — dossier créé automatiquement à la création
 *       de la parcelle, aucune action encore entreprise.</li>
 *   <li>{@link #IN_PROGRESS} — collecte des pièces en cours (titre
 *       foncier, attestation mairie, analyse satellite…).</li>
 *   <li>{@link #COMPLIANT} — vérifications passées avec succès : pas de
 *       déforestation post-31/12/2020, foncier régulier, droits humains
 *       respectés. La parcelle peut servir d'origine pour des lots
 *       exportés vers l'UE.</li>
 *   <li>{@link #NON_COMPLIANT} — non-conformité détectée. Les lots issus
 *       de cette parcelle ne doivent pas partir vers l'UE. Une raison
 *       est obligatoire ({@code exclusionReason}).</li>
 *   <li>{@link #EXPIRED} — la dernière revue date de plus de 12 mois,
 *       à actualiser (alerte direction). Bloquant en pratique pour les
 *       nouveaux exports.</li>
 * </ul>
 */
public enum EudrStatus {
    NOT_STARTED,
    IN_PROGRESS,
    COMPLIANT,
    NON_COMPLIANT,
    EXPIRED
}
