package com.ntech.cabosse.region.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.UUID;

/**
 * Région administrative du tenant (référentiel). Alimente la liste
 * déroulante « région » du profil coopérative et des parcelles.
 *
 * <p>Tenant-scoped, éditable. Liste <strong>propre au tenant</strong>,
 * indépendante du catalogue global de régions du plan contrôle (décision
 * du 21/07/2026) et du référentiel des
 * {@link com.ntech.cabosse.department.entity.DepartmentEntity départements}
 * (pas de hiérarchie imposée). Pas de seed : la liste se construit à l'usage.</p>
 */
public class RegionEntity {

    @BsonId
    public UUID id;

    /** Code stable (slug) ; FK technique éventuelle. */
    public String code;

    /** Nom affiché et stocké (ex. {@code "Nawa"}). */
    public String name;

    public boolean active = true;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;

    public RegionEntity() {}
}
