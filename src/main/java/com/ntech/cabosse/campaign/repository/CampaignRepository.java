package com.ntech.cabosse.campaign.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.entity.CampaignStatus;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.time.LocalDate;
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

    /** Campagnes ouvertes, la plus récemment démarrée en tête. */
    public List<CampaignEntity> listOpen() {
        return coll()
                .find(Filters.eq("status", CampaignStatus.OPEN.name()))
                .sort(new Document("startDate", -1))
                .into(new ArrayList<>());
    }

    /**
     * Campagne en cours à la date du jour.
     *
     * <p>Plusieurs campagnes peuvent être ouvertes en même temps : la
     * principale et l'intermédiaire d'une même saison ont chacune leur
     * période et leur prix bord champ. On retient donc celle dont la
     * période couvre aujourd'hui ; à défaut la plus récemment démarrée,
     * pour qu'une opération saisie hors période trouve tout de même un
     * rattachement par défaut, corrigeable à la main.</p>
     */
    public Optional<CampaignEntity> findCurrent() {
        List<CampaignEntity> open = listOpen();
        LocalDate today = LocalDate.now();
        return open.stream()
                .filter(c -> covers(c, today))
                .findFirst()
                .or(() -> open.stream().findFirst());
    }

    private static boolean covers(CampaignEntity c, LocalDate day) {
        boolean started = c.startDate == null || !day.isBefore(c.startDate);
        boolean notEnded = c.endDate == null || !day.isAfter(c.endDate);
        return started && notEnded;
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

    public void insert(CampaignEntity e) {
        coll().insertOne(e);
    }

    public void replace(CampaignEntity e) {
        coll().replaceOne(Filters.eq("_id", e.id), e);
    }
}
