package com.ntech.cabosse.agriculture.parcel.entity;

/**
 * État opérationnel d'une parcelle.
 *
 * <ul>
 *   <li>{@link #ACTIVE} — en production active.</li>
 *   <li>{@link #FALLOW} — en jachère (sol en repos).</li>
 *   <li>{@link #REPLANTING} — replanté récemment, non encore productif.</li>
 *   <li>{@link #ABANDONED} — abandonnée, plus exploitée mais conservée
 *       pour traçabilité (EUDR exige l'historique).</li>
 * </ul>
 */
public enum ParcelStatus {
    ACTIVE,
    FALLOW,
    REPLANTING,
    ABANDONED
}
