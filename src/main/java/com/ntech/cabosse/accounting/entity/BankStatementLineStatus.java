package com.ntech.cabosse.accounting.entity;

/**
 * États d'une ligne d'extrait bancaire.
 *
 * <ul>
 *   <li>{@link #UNMATCHED} — non rapprochée à une pièce comptable.</li>
 *   <li>{@link #MATCHED} — rapprochée ; {@code matchedPieceId} et
 *       {@code matchedAt} sont renseignés.</li>
 *   <li>{@link #IGNORED} — déclarée volontairement comme à ignorer
 *       (frais bancaires non comptabilisés à part, virement entre
 *       comptes propres…).</li>
 *   <li>{@link #DISPUTE} — litigieuse (montant inattendu, opération
 *       non reconnue) — à traiter avec la banque.</li>
 * </ul>
 */
public enum BankStatementLineStatus {
    UNMATCHED,
    MATCHED,
    IGNORED,
    DISPUTE
}
