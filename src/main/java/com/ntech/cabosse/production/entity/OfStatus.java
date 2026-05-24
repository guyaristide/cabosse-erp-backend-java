package com.ntech.cabosse.production.entity;

/**
 * Statut macro d'un ordre de fabrication.
 *
 * <p>Le suivi par étapes (si la recette en définit) vit dans
 * {@code currentStepIndex} et {@code stepHistory} sur l'entité — ce
 * sont des dimensions orthogonales : l'OF reste en {@link #IN_PROGRESS}
 * quelle que soit l'étape courante, jusqu'à passer en {@link #COMPLETED}.</p>
 */
public enum OfStatus {

    /** Création initiale. Aucun impact stock. Édition possible. */
    DRAFT,

    /** Démarré : matières consommées (mouvements OUT posés). Étape 0 active si recette avec étapes. */
    IN_PROGRESS,

    /** Production terminée : PF entré en stock avec CMUP recalculé. */
    COMPLETED,

    /** Contre-passé : mouvements compensatoires posés (IN matières + OUT PF si pertinent). */
    CANCELLED
}
