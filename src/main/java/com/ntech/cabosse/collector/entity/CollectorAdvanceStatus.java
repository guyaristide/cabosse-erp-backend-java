package com.ntech.cabosse.collector.entity;

/** Statut d'une avance délégué (backlog ACH-02). */
public enum CollectorAdvanceStatus {
    /** Avance versée, en cours de consommation par les livraisons. */
    OPEN,
    /** Avance clôturée — le solde résiduel reste une créance sur le délégué. */
    CLOSED
}
