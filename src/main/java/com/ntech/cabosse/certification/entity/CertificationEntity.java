package com.ntech.cabosse.certification.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.UUID;

/**
 * Certification du tenant (référentiel). Remplace la saisie libre des
 * certifications de parcelle par une liste partagée et dédupliquée :
 * « Rainforest », « RA » et « rainforest alliance » cessent d'être trois
 * certifications différentes.
 *
 * <p>Tenant-scoped. Le {@code name} est la valeur stockée sur les
 * documents métier (ex. {@code parcel.certifications = ["Rainforest
 * Alliance"]}). Pas de seed : chaque filière a les siennes, la liste se
 * construit à l'usage.</p>
 */
public class CertificationEntity {

    @BsonId
    public UUID id;

    /** Code stable (slug). */
    public String code;

    /** Libellé affiché et stocké (ex. {@code "Rainforest Alliance"}). */
    public String name;

    public boolean active = true;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;

    public CertificationEntity() {}
}
