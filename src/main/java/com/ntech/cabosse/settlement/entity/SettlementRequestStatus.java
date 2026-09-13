package com.ntech.cabosse.settlement.entity;

/** Où en est une demande de règlement. */
public enum SettlementRequestStatus {
    /** Déposée, elle attend une décision. */
    PENDING_APPROVAL,
    /** Accordée : le montant approuvé peut sortir. */
    APPROVED,
    /** Refusée, avec son motif. Elle reste au registre. */
    REJECTED,
    /** Le règlement a été enregistré : la demande a fait son office. */
    PAID,
    /** Retirée par son demandeur avant décision. */
    CANCELLED
}
