package com.ntech.cabosse.processing.fermentation.entity;

/** Cycle de vie d'un bac de fermentation. */
public enum FermentationBatchStatus {
    /** Bac créé, en attente du chargement des récoltes. */
    PREPARING,
    /** Fermentation en cours — relevés température et brassages actifs. */
    ACTIVE,
    /** Fermentation terminée — bac vidé, fèves passent au séchage. */
    COMPLETED,
    /** Annulé (rare — perte qualité, contamination). */
    CANCELLED
}
