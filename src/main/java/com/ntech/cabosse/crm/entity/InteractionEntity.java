package com.ntech.cabosse.crm.entity;

import com.ntech.cabosse.shared.persistence.ControlPlane;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Un échange daté avec un prospect ou une structure : appel, réunion,
 * message, visite (SAAS-31).
 *
 * <p>Porte l'un ou l'autre rattachement, parfois les deux : quand un
 * prospect devient une structure, ses échanges reçoivent aussi le
 * {@link #tenantId}, pour que l'histoire commerciale se lise d'un seul
 * tenant sur la fiche de la structure.</p>
 */
@MongoEntity(database = ControlPlane.DATABASE, collection = ControlPlane.Collections.CRM_INTERACTIONS)
public class InteractionEntity extends PanacheMongoEntityBase {

    @BsonId
    public UUID id;

    public UUID prospectId;
    public UUID tenantId;

    public InteractionKind kind;
    public LocalDate date;
    public String summary;

    public String authorEmail;
    public String authorName;
    public Instant createdAt;
}
