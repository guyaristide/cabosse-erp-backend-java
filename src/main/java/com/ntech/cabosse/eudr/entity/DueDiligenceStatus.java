package com.ntech.cabosse.eudr.entity;

/**
 * État d'une Déclaration de Diligence Raisonnée (DDR) EUDR.
 *
 * <ul>
 *   <li>{@link #DRAFT} — générée automatiquement à la confirmation d'une
 *       vente export EU, à compléter par le responsable export.</li>
 *   <li>{@link #READY} — prête à être soumise au portail UE EUDR
 *       Information System.</li>
 *   <li>{@link #SUBMITTED} — soumise, en attente de validation UE.
 *       {@code eudrReferenceNumber} renseigné.</li>
 *   <li>{@link #ACCEPTED} — validée par l'UE, l'export peut partir.</li>
 *   <li>{@link #REJECTED} — refusée par l'UE, action corrective requise.
 *       L'export ne peut pas partir tant que non régularisé.</li>
 * </ul>
 */
public enum DueDiligenceStatus {
    DRAFT,
    READY,
    SUBMITTED,
    ACCEPTED,
    REJECTED
}
