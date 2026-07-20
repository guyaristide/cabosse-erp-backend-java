package com.ntech.cabosse.analytics.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Clé de répartition analytique (backlog CPT-17) : ventile une charge
 * indirecte sur plusieurs centres de coût au prorata de poids éditables.
 * Tenant-scopé (collection {@code allocation_keys}).
 *
 * <p>Les poids sont des valeurs relatives — la coopérative y encode la
 * clé de son choix (par effectifs, par volume d'activité, par surface…),
 * la méthode restant documentée dans {@link #method}. La ventilation
 * normalise les poids : une ligne reçoit {@code poids / Σ poids} du
 * montant. Aucune méthode n'est figée dans le code, conformément au
 * principe de configuration par le tenant.</p>
 */
public class AllocationKeyEntity {

    @BsonId
    public UUID id;

    /** Code court stable (ex. {@code ADMIN}, {@code STRUCT}). */
    public String code;

    public String name;
    public String description;

    /** Libellé libre de la méthode (« par effectifs », « par volume »…). Documentaire. */
    public String method;

    public boolean active = true;

    /** Lignes de ventilation : un centre de coût et son poids relatif. */
    public List<Line> lines = new ArrayList<>();

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;

    /** Ligne d'une clé : un centre de coût et son poids (valeur relative). */
    public static class Line {
        public String costCenter;
        public BigDecimal weight;

        public Line() {}

        public Line(String costCenter, BigDecimal weight) {
            this.costCenter = costCenter;
            this.weight = weight;
        }
    }
}
