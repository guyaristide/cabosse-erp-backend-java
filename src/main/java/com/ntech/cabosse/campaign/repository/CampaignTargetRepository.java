package com.ntech.cabosse.campaign.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.ntech.cabosse.campaign.entity.CampaignTargetEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class CampaignTargetRepository {

    public static final String COLLECTION = CampaignTargetEntity.COLLECTION;

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<CampaignTargetEntity> coll() {
        return tenantDb.collection(COLLECTION, CampaignTargetEntity.class);
    }

    public java.util.Optional<CampaignTargetEntity> find(UUID campaignId, String month) {
        return java.util.Optional.ofNullable(coll().find(Filters.and(
                Filters.eq("campaignId", campaignId), Filters.eq("month", month))).first());
    }

    public List<CampaignTargetEntity> listByCampaign(UUID campaignId) {
        return coll().find(Filters.eq("campaignId", campaignId))
                .sort(new Document("month", 1))
                .into(new ArrayList<>());
    }

    /**
     * Un seul objectif par mois et par campagne : le couple est la clé,
     * et réenregistrer remplace au lieu d'empiler une seconde cible.
     */
    public void upsert(CampaignTargetEntity e) {
        coll().replaceOne(
                Filters.and(Filters.eq("campaignId", e.campaignId), Filters.eq("month", e.month)),
                e, new ReplaceOptions().upsert(true));
    }

    /** Un objectif effacé disparaît : il n'y a pas d'objectif « à zéro » par défaut. */
    public void delete(UUID campaignId, String month) {
        coll().deleteOne(Filters.and(
                Filters.eq("campaignId", campaignId), Filters.eq("month", month)));
    }
}
