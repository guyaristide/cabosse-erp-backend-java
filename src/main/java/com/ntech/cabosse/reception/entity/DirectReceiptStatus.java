package com.ntech.cabosse.reception.entity;

/**
 * Statut de paiement d'une session de réception directe — <strong>dérivé</strong>
 * de l'état des paiements de chaque ligne :
 * <ul>
 *   <li>{@code UNPAID} — aucune ligne n'a de paiement enregistré.</li>
 *   <li>{@code PARTIAL} — au moins une ligne payée, au moins une non payée.</li>
 *   <li>{@code PAID} — toutes les lignes ont leur paiement enregistré.</li>
 *   <li>{@code CANCELLED} — contre-passation administrative (annulation
 *       d'une réception erronée, conserve la trace).</li>
 * </ul>
 *
 * <p>Le calcul du statut se fait à la sauvegarde par
 * {@code DirectReceiptService}. L'utilisateur ne le saisit jamais
 * directement.</p>
 */
public enum DirectReceiptStatus {
    UNPAID, PARTIAL, PAID, CANCELLED
}
