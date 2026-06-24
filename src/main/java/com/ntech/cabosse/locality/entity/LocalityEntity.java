package com.ntech.cabosse.locality.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.UUID;

/**
 * Localité / village du tenant (référentiel). Remplace la saisie libre du
 * champ {@code village} d'un membre par une liste partagée et dédupliquée.
 *
 * <p>Tenant-scoped. Le {@code name} est la valeur stockée sur les
 * documents métier (ex. {@code member.village = "Méagui"}). Les villages
 * sont propres au tenant — pas de seed, la liste se construit à l'usage.</p>
 */
public class LocalityEntity {

    @BsonId
    public UUID id;

    /** Code stable (slug) ; FK technique éventuelle. */
    public String code;

    /** Nom affiché et stocké (ex. {@code "Méagui"}). */
    public String name;

    public boolean active = true;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;

    public LocalityEntity() {}
}
