package com.ntech.cabosse.members.entity;

/**
 * Ménage du producteur (backlog MEM-08). Sub-document de
 * {@link MemberEntity}, collecté lors de l'enquête d'enrôlement.
 *
 * <p>Ces comptages ne sont pas décoratifs : la répartition des enfants par
 * tranche d'âge et leur scolarisation alimentent le suivi du travail des
 * enfants, et la taille du ménage entre dans le calcul du revenu vital.</p>
 *
 * <p>Tous les champs sont facultatifs (une enquête peut être partielle),
 * mais ceux qui sont renseignés doivent être cohérents entre eux — la
 * vérification est faite par {@code MemberHouseholdRules}.</p>
 */
public class MemberHousehold {

    /** Nombre d'épouses déclarées. */
    public Integer spousesCount;

    /** Nombre total d'enfants. */
    public Integer childrenCount;

    public Integer girlsCount;
    public Integer boysCount;

    /** Enfants de 0 à 4 ans. */
    public Integer children0to4;

    /** Enfants de 5 à 17 ans. */
    public Integer children5to17;

    /** Enfants de plus de 17 ans. */
    public Integer childrenOver17;

    public Integer childrenSchooled;
    public Integer childrenNotSchooled;

    /**
     * Activité à laquelle les enfants sont soumis, telle que déclarée
     * (« Aucune activité », « Travaux domestiques »…). Saisie libre au MVP.
     */
    public String childrenActivity;

    public MemberHousehold() {}

    /** Vrai si aucune valeur n'a été saisie : le bloc est alors considéré absent. */
    public boolean isEmpty() {
        return spousesCount == null && childrenCount == null && girlsCount == null
                && boysCount == null && children0to4 == null && children5to17 == null
                && childrenOver17 == null && childrenSchooled == null
                && childrenNotSchooled == null
                && (childrenActivity == null || childrenActivity.isBlank());
    }
}
