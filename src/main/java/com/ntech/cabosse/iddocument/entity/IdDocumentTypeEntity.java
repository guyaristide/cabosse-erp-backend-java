package com.ntech.cabosse.iddocument.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.UUID;

/**
 * Type de pièce d'identité accepté par le tenant (référentiel).
 * Alimente la liste déroulante « type de pièce » de la fiche membre
 * (CNI, passeport, attestation d'identité, carte consulaire…).
 *
 * <p>Tenant-scoped, éditable, la coopérative complète la liste des types
 * qu'elle accepte. Pas de seed : la liste se construit à l'usage.</p>
 */
public class IdDocumentTypeEntity {

    @BsonId
    public UUID id;

    /** Code stable (slug) ; FK technique éventuelle. */
    public String code;

    /** Nom affiché et stocké (ex. {@code "CNI"}, {@code "Passeport"}). */
    public String name;

    public boolean active = true;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;

    public IdDocumentTypeEntity() {}
}
