package com.ntech.cabosse.purchaserequest.entity;

/**
 * Statut d'une demande d'achat (backlog ACH-01, circuit de contrôle
 * interne v15). Cycle : {@code DRAFT → SUBMITTED → APPROVED / REJECTED},
 * puis {@code APPROVED → CONVERTED} lorsqu'un bon de commande en est issu.
 */
public enum PurchaseRequestStatus {
    DRAFT,
    SUBMITTED,
    APPROVED,
    REJECTED,
    CONVERTED
}
