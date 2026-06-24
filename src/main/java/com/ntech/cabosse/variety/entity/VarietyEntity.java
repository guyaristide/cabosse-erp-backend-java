package com.ntech.cabosse.variety.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.UUID;

/**
 * Variété cultivée du tenant (référentiel). Remplace la saisie libre du
 * champ {@code variety} d'une parcelle.
 *
 * <p>Tenant-scoped, filière-agnostique (cacao Mercedes/F1, hévéa GT1…).
 * Le {@code name} est la valeur stockée sur les parcelles. Pas de seed —
 * la liste se construit à l'usage. Un éventuel tag filière
 * ({@code activityCode}) pourra être ajouté ultérieurement.</p>
 */
public class VarietyEntity {

    @BsonId
    public UUID id;

    public String code;
    public String name;

    public boolean active = true;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;

    public VarietyEntity() {}
}
