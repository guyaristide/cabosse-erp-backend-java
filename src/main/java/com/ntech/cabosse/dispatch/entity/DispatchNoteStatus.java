package com.ntech.cabosse.dispatch.entity;

/**
 * Cycle d'un bordereau de sortie (CE-195). {@code OPEN} : chargé, en
 * attente de sa vente. {@code SOLD} : une vente l'a appelé, il ne bouge
 * plus. {@code CANCELLED} : contre-passé avant vente, le stock et les
 * reliquats des reçus sont revenus.
 */
public enum DispatchNoteStatus {
    OPEN,
    SOLD,
    CANCELLED
}
