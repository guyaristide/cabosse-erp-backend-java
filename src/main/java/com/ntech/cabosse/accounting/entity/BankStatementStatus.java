package com.ntech.cabosse.accounting.entity;

/**
 * États du cycle de vie d'un extrait bancaire importé.
 *
 * <ul>
 *   <li>{@link #IMPORTED} — extrait juste importé, lignes encore en
 *       majorité non rapprochées.</li>
 *   <li>{@link #IN_PROGRESS} — rapprochement en cours (au moins une ligne
 *       matchée, mais reste des non-rapprochées).</li>
 *   <li>{@link #RECONCILED} — toutes les lignes sont MATCHED ou IGNORED.</li>
 *   <li>{@link #ARCHIVED} — extrait clôturé manuellement par l'utilisateur,
 *       plus visible par défaut dans la liste.</li>
 * </ul>
 */
public enum BankStatementStatus {
    IMPORTED,
    IN_PROGRESS,
    RECONCILED,
    ARCHIVED
}
