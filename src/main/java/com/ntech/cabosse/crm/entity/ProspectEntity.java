package com.ntech.cabosse.crm.entity;

import com.ntech.cabosse.shared.persistence.ControlPlane;
import com.ntech.cabosse.tenant.entity.TenantOrganizationModel;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Un prospect : une organisation approchée par l'éditeur, avant qu'elle
 * ne devienne une structure (SAAS-31).
 *
 * <p>Vit dans le plan de contrôle, comme les tickets : il n'appartient à
 * aucune structure, et c'est l'éditeur qui tient le fil. Quand le
 * prospect est gagné et sa structure créée, {@link #tenantId} fait le
 * lien et le fil des échanges suit la structure.</p>
 */
@MongoEntity(database = ControlPlane.DATABASE, collection = ControlPlane.Collections.CRM_PROSPECTS)
public class ProspectEntity extends PanacheMongoEntityBase {

    @BsonId
    public UUID id;

    public String name;
    public TenantOrganizationModel organizationModel;
    /** La filière, en clair : cacao, hévéa, savon… Rien n'oblige à un catalogue à ce stade. */
    public String industry;
    public String country;
    public String city;
    /** D'où vient le contact : salon, recommandation, site web, démarchage… */
    public String source;

    public ProspectStage stage;
    public Instant stageChangedAt;
    public String lostReason;

    public List<ProspectContact> contacts = new ArrayList<>();

    /** Qui suit le dossier chez l'éditeur. */
    public String ownerEmail;
    public LocalDate nextActionAt;
    public String nextActionLabel;
    public String notes;

    /** La structure née de ce prospect, une fois créée. */
    public UUID tenantId;

    public Instant createdAt;
    public Instant updatedAt;
    public String createdByEmail;
    public String updatedByEmail;
}
