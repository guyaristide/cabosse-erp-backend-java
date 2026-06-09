package com.ntech.cabosse.agriculture.qc.entity;

/**
 * Grade qualité d'un lot de fèves après contrôle.
 *
 * <ul>
 *   <li>{@link #GR1} — premier grade, qualité export premium.</li>
 *   <li>{@link #GR2} — second grade, qualité commercial standard.</li>
 *   <li>{@link #HG} — hors grade, fèves défectueuses (moisies, mal
 *       fermentées, plates). Souvent utilisées en sous-produit beurre /
 *       poudre ou refusées.</li>
 * </ul>
 */
public enum BeanGrade {
    GR1,
    GR2,
    HG
}
