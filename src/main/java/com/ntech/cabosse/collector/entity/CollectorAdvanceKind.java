package com.ntech.cabosse.collector.entity;

/**
 * Ce que la coopérative remet à un délégué.
 *
 * <p>L'avance de collecte finance les achats aux producteurs et se solde
 * en matière, au fil des reçus. Le prêt répond à un besoin propre du
 * délégué, comme le crédit d'un producteur (demandé le 24/10/2026) : même
 * circuit de décision et de décaissement, pas de contrepartie attendue en
 * kilos, et il se rembourse sur les livraisons une fois les avances de
 * collecte soldées, ou en espèces.</p>
 */
public enum CollectorAdvanceKind {
    COLLECTION,
    LOAN
}
