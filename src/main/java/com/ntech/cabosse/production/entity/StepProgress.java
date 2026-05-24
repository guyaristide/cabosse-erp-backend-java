package com.ntech.cabosse.production.entity;

import java.time.Instant;

/**
 * Progression d'une étape de production sur un ordre de fabrication.
 * Une entrée par étape franchie, ajoutée au démarrage de l'étape.
 *
 * <p>Pour une recette avec N étapes, l'OF a au plus N entrées dans
 * {@code stepHistory}. La dernière entrée a {@code completedAt = null}
 * tant que l'étape est en cours.</p>
 */
public class StepProgress {

    /** Index de l'étape dans {@code recipeStepsSnapshot}. */
    public int stepOrder;

    /** Snapshot du nom de l'étape — préserve l'audit même si la recette évolue. */
    public String stepName;

    public Instant startedAt;

    /** Null tant que l'étape est active. */
    public Instant completedAt;

    /** Observations terrain optionnelles. */
    public String notes;

    public StepProgress() {}
}
