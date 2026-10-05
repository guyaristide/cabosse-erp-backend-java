package com.ntech.cabosse.cashsupply.entity;

/**
 * Où en est une demande d'approvisionnement de la caisse.
 *
 * <p>Accordée n'est pas exécutée : entre la décision et l'argent dans le
 * tiroir, il y a un chèque à préparer et un déplacement à la banque, qui
 * prend parfois le lendemain. C'est ce moment que la coopérative veut
 * voir, comme elle voit déjà un transport de fonds en route.</p>
 */
public enum CashSupplyStatus {

    /** Déposée par la direction, en attente de la décision. */
    PENDING_APPROVAL,

    /** Accordée : la caisse peut préparer le chèque. */
    APPROVED,

    /** Le retrait est parti : un transport de fonds porte la suite. */
    FULFILLED,

    REJECTED,

    /** Retirée par qui l'a déposée, avant toute décision. */
    CANCELLED
}
