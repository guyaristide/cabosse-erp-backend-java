package com.ntech.cabosse.campaign.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.entity.CampaignStatus;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Accès Mongo aux campagnes (tenant-scopé). Voir {@link CampaignEntity}.
 */
@ApplicationScoped
public class CampaignRepository {

    public static final String COLLECTION = "campaigns";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<CampaignEntity> coll() {
        return tenantDb.collection(COLLECTION, CampaignEntity.class);
    }

    public Optional<CampaignEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public Optional<CampaignEntity> findByCode(String code) {
        return Optional.ofNullable(coll().find(Filters.eq("code", code)).first());
    }

    /** Renvoie la campagne {@link CampaignStatus#OPEN} courante, s'il en existe une. */
    public Optional<CampaignEntity> findCurrent() {
        return Optional.ofNullable(
                coll().find(Filters.eq("status", CampaignStatus.OPEN.name()))
                        .sort(new Document("startDate", -1))
                        .first()
        );
    }

    /** Liste triée par année décroissante puis startDate décroissante. */
    public List<CampaignEntity> listAll() {
        return coll()
                .find()
                .sort(new Document("campaignYear", -1).append("startDate", -1))
                .into(new ArrayList<>());
    }

    public List<CampaignEntity> listByYear(int year) {
        return coll()
                .find(Filters.eq("campaignYear", year))
                .sort(new Document("startDate", -1))
                .into(new ArrayList<>());
    }

    public boolean hasOpenOtherThan(UUID excludeId) {
        Document filter = new Document("status", CampaignStatus.OPEN.name());
        if (excludeId != null) {
            filter.append("_id", new Document("$ne", excludeId));
        }
        return coll().countDocuments(filter) > 0;
    }

    public void insert(CampaignEntity e) {
        coll().insertOne(e);
    }

    public void replace(CampaignEntity e) {
        coll().replaceOne(Filters.eq("_id", e.id), e);
    }
}
