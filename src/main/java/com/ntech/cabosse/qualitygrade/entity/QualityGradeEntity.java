package com.ntech.cabosse.qualitygrade.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.UUID;

/**
 * Grade de qualité d'une matière, tel que la structure le nomme.
 *
 * <p>Référentiel tenant, éditable, <strong>sans seed</strong> : chaque
 * filière a sa nomenclature et personne d'autre n'a à la lui dicter. Le
 * cacao ivoirien parle de GR1, GR2 et hors grade ; l'hévéa classe en RSS1
 * à RSS5 ; l'anacarde raisonne en calibre et en taux de rendement. Une
 * liste figée dans le code aurait obligé chacune à se plier au vocabulaire
 * de la première filière branchée.</p>
 *
 * <p>Consommé par le contrôle qualité, qui classe un lot, et par la
 * grille tarifaire d'une campagne, qui attache une prime au grade. Les
 * deux lisaient jusqu'ici deux nomenclatures distinctes, dont aucune
 * n'était vérifiée.</p>
 */
public class QualityGradeEntity {

    @BsonId
    public UUID id;

    /**
     * Code stable, référencé par les contrôles qualité et les primes de
     * campagne. En majuscules : c'est ainsi qu'il s'écrit sur un
     * bordereau.
     */
    public String code;

    /** Libellé affiché ({@code "Premier grade"}, {@code "Hors grade"}). */
    public String label;

    /**
     * Rang d'affichage, du meilleur grade au moins bon.
     *
     * <p>L'ordre alphabétique dirait « GR1, GR2, HG », ce qui tombe juste
     * par accident ; il dirait aussi « HG, RSS1 » ailleurs. Le rang se
     * déclare plutôt que de se deviner.</p>
     */
    public int sortOrder;

    public boolean active = true;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;

    public QualityGradeEntity() {}
}
