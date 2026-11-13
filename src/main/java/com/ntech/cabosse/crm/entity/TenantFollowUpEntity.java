package com.ntech.cabosse.crm.entity;

import com.ntech.cabosse.shared.persistence.ControlPlane;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * La prochaine action prévue sur une structure existante : relance,
 * renouvellement, visite (SAAS-31). Un document par structure, créé au
 * premier besoin ; la fiche de la structure, elle, ne change pas.
 */
@MongoEntity(database = ControlPlane.DATABASE, collection = ControlPlane.Collections.CRM_TENANT_FOLLOWUPS)
public class TenantFollowUpEntity extends PanacheMongoEntityBase {

    @BsonId
    public UUID id;

    public UUID tenantId;
    public String ownerEmail;
    public LocalDate nextActionAt;
    public String nextActionLabel;

    public Instant updatedAt;
    public String updatedByEmail;
}
