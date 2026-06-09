package com.ntech.cabosse.members.entity;

/**
 * État d'un membre dans la structure (coopérative, GIE, association).
 *
 * <ul>
 *   <li>{@link #ACTIVE} — membre en règle, peut livrer et être rémunéré.</li>
 *   <li>{@link #SUSPENDED} — temporairement écarté (sanction, contentieux),
 *       les opérations en cours s'achèvent mais aucune nouvelle saisie
 *       n'est acceptée.</li>
 *   <li>{@link #INACTIVE} — départ volontaire ou décès. L'historique reste
 *       consultable, plus de nouvelles opérations.</li>
 * </ul>
 */
public enum MemberStatus {
    ACTIVE,
    SUSPENDED,
    INACTIVE
}
