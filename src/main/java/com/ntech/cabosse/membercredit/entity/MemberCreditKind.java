package com.ntech.cabosse.membercredit.entity;

/**
 * Nature de l'engagement financier consenti à un producteur membre.
 *
 * <ul>
 *   <li>{@link #CREDIT} — finance un besoin important et durable du
 *       producteur (moyen de transport, toiture, intrants, dépense de
 *       santé, funérailles). Montant conséquent, remboursé par retenues
 *       successives sur plusieurs livraisons.</li>
 *   <li>{@link #ADVANCE} — couvre un besoin de campagne (rémunération
 *       d'un groupe d'entraide, restauration, carburant pour aller
 *       chercher un stock). Montant modeste, remboursé en règle générale
 *       sur une seule livraison.</li>
 * </ul>
 *
 * <p>La distinction n'est pas un simple libellé : elle porte l'attente de
 * remboursement, et donc la lecture qu'un gérant fait d'un solde qui
 * traîne.</p>
 */
public enum MemberCreditKind {
    CREDIT,
    ADVANCE
}
