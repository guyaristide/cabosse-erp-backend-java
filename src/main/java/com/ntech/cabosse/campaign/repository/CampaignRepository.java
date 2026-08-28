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

    /**
     * Campagne dont la période couvre une date donnée.
     *
     * <p>À la différence de {@link #findCurrent()}, aucun repli : une
     * opération datée hors de toute campagne ne se rattache à rien plutôt
     * que d'aller grossir la campagne la plus récente. Un rattachement
     * faux coûte plus cher qu'un rattachement absent — il fausse
     * silencieusement les états de campagne, alors qu'un trou se voit.</p>
     *
     * <p>Les campagnes closes comptent : une opération saisie
     * rétroactivement appartient à sa campagne même si celle-ci est
     * refermée depuis. Seule la période fait foi.</p>
     */
    public Optional<CampaignEntity> findForDate(LocalDate day) {
        if (day == null) {
            return Optional.empty();
        }
        return coll()
                .find()
                .sort(new Document("startDate", -1))
                .into(new ArrayList<CampaignEntity>())
                .stream()
                .filter(c -> covers(c, day))
                .findFirst();
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

    /**
     * Applique un nouveau barème et empile son historique, gardé par le
     * prix observé à la lecture.
     *
     * <p>Deux personnes qui changent le prix en même temps ne doivent pas
     * en voir une écraser l'autre en silence : la seconde ne retrouve plus
     * le prix qu'elle avait sous les yeux et repart bredouille. Jamais de
     * read-modify-replace ici, c'est un {@code updateOne} conditionnel.</p>
     *
     * @return false si le barème a bougé entre la lecture et l'écriture
     */
    public boolean applyTariff(UUID id, java.math.BigDecimal expectedBasePrice,
                               java.math.BigDecimal newBasePrice,
                               java.math.BigDecimal newRistournePct,
                               List<com.ntech.cabosse.campaign.entity.QualityPremium> newPremiums,
                               com.ntech.cabosse.campaign.entity.TariffChange change,
                               java.time.Instant updatedAt) {
        return coll().updateOne(
                Filters.and(
                        Filters.eq("_id", id),
                        Filters.eq("basePricePerKgFcfa", expectedBasePrice)),
                com.mongodb.client.model.Updates.combine(
                        com.mongodb.client.model.Updates.set("basePricePerKgFcfa", newBasePrice),
                        com.mongodb.client.model.Updates.set("ristournePct", newRistournePct),
                        com.mongodb.client.model.Updates.set("qualityPremiums", newPremiums),
                        com.mongodb.client.model.Updates.set("updatedAt", updatedAt),
                        com.mongodb.client.model.Updates.push("tariffHistory", change))
        ).getModifiedCount() == 1;
    }
}
