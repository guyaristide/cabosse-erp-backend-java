package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.Updates;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Migration 072 — principale ou intermédiaire, dit par le modèle.
 *
 * <p>Une saison se joue en une campagne principale puis une ou plusieurs
 * intermédiaires. La distinction n'était portée que par le libellé, donc
 * par personne : aucun état ne pouvait trier là-dessus, et rien
 * n'empêchait deux principales sur une même année.</p>
 *
 * <p><strong>La reprise est une inférence, pas une lecture.</strong> Rien
 * en base ne dit laquelle des campagnes d'une année est la principale. La
 * règle retenue est la seule qui tienne debout : la <em>première démarrée
 * de l'année</em> est la principale, les suivantes sont intermédiaires,
 * une saison s'ouvrant toujours par sa campagne de gros.</p>
 *
 * <p>Ce choix est visible et corrigeable d'un clic sur la fiche : la
 * mention est descriptive, elle ne déplace aucun montant. C'est ce qui
 * permet de l'inférer ici plutôt que de laisser le champ vide et de
 * bloquer les écrans.</p>
 */
@ChangeUnit(id = "campaign_kind", order = "072", author = "neiba")
public class M072_CampaignKind {

    private static final String CAMPAIGNS = "campaigns";

    @Execution
    public void execute(MongoDatabase database) {
        // Groupées par année, dans l'ordre où elles ont démarré. Une année
        // sans date de début garde ses campagnes en fin de groupe : rien
        // ne dit alors laquelle est la première.
        Map<Integer, List<Document>> byYear = new LinkedHashMap<>();
        for (Document campaign : database.getCollection(CAMPAIGNS)
                .find(Filters.exists("kind", false))) {
            Integer year = campaign.getInteger("campaignYear");
            byYear.computeIfAbsent(year != null ? year : 0, y -> new ArrayList<>()).add(campaign);
        }
        if (byYear.isEmpty()) return;

        List<UpdateOneModel<Document>> updates = new ArrayList<>();
        for (List<Document> ofYear : byYear.values()) {
            ofYear.sort(Comparator.comparing(
                    d -> d.getString("startDate"),
                    Comparator.nullsLast(Comparator.naturalOrder())));
            for (int i = 0; i < ofYear.size(); i++) {
                updates.add(new UpdateOneModel<>(
                        Filters.eq("_id", ofYear.get(i).get("_id")),
                        Updates.set("kind", i == 0 ? "MAIN" : "INTERMEDIATE")));
            }
        }
        database.getCollection(CAMPAIGNS).bulkWrite(updates);
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection(CAMPAIGNS).updateMany(
                Filters.exists("kind", true),
                Updates.unset("kind"));
    }
}
