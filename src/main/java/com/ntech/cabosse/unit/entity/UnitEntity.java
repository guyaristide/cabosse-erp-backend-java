package com.ntech.cabosse.unit.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.UUID;

/**
 * Unité de mesure du tenant (référentiel). Remplace la saisie libre de
 * {@code Article.unit} / unité de recette par une liste gérée une fois.
 *
 * <p>Tenant-scoped, filière-agnostique. Le {@code code} est la valeur
 * stockée sur les documents métier (ex. {@code "kg"}) ; le {@code name}
 * est le libellé affiché ({@code "Kilogramme"}).</p>
 */
public class UnitEntity {

    @BsonId
    public UUID id;

    /** Code court stocké sur les entités métier (ex. {@code "kg"}, {@code "sac"}). */
    public String code;

    /** Libellé affiché (ex. {@code "Kilogramme"}). */
    public String name;

    public boolean active = true;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;

    public UnitEntity() {}

    public UnitEntity(UUID id, String code, String name, Instant now) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.active = true;
        this.createdAt = now;
        this.updatedAt = now;
    }
}
