package com.ntech.cabosse.operator.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.UUID;

/**
 * Opérateur du tenant (référentiel léger). Remplace la saisie libre des
 * champs « opérateur » des opérations terrain (brassage de fermentation,
 * et à terme production, QC…).
 *
 * <p>Tenant-scoped. Le {@code name} est la valeur stockée sur les
 * opérations. Référentiel volontairement minimal (nom) — distinct d'un
 * futur module Paie/RH (employés, CNPS) qui portera une fiche complète.
 * Pas de seed : la liste se construit à l'usage (ajout inline).</p>
 */
public class OperatorEntity {

    @BsonId
    public UUID id;

    public String code;
    public String name;

    public boolean active = true;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;

    public OperatorEntity() {}
}
