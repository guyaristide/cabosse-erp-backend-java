package com.ntech.cabosse.department.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.UUID;

/**
 * Département administratif du tenant (référentiel). Alimente la liste
 * déroulante « département » du profil coopérative et des parcelles.
 *
 * <p>Tenant-scoped, éditable. Liste <strong>indépendante</strong> du
 * référentiel des {@link com.ntech.cabosse.region.entity.RegionEntity régions}
 * (pas de hiérarchie région/département imposée, décision du 21/07/2026).
 * Pas de seed : la liste se construit à l'usage.</p>
 */
public class DepartmentEntity {

    @BsonId
    public UUID id;

    /** Code stable (slug) ; FK technique éventuelle. */
    public String code;

    /** Nom affiché et stocké (ex. {@code "Soubré"}). */
    public String name;

    public boolean active = true;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;

    public DepartmentEntity() {}
}
