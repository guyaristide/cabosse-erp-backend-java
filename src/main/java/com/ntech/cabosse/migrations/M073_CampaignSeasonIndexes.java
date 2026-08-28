package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;

/**
 * Migration 073 — la base cesse d'interdire deux campagnes ouvertes.
 *
 * <p>Un index unique partiel {@code uniq_campaigns_open} n'autorisait
 * qu'une seule campagne au statut ouvert. Il datait d'une époque où
 * c'était la règle. La règle a changé : une saison se joue en une
 * principale puis une ou plusieurs intermédiaires, et <strong>la
 * principale n'est pas close le jour où l'intermédiaire démarre</strong>,
 * parce qu'on saisit encore des reçus de la principale après cette
 * date.</p>
 *
 * <p>Le code, l'entité et les tests décrivaient déjà la nouvelle règle.
 * L'index, lui, était resté. Il ne se voyait pas en test : la migration
 * qui le pose est conditionnée à une capacité que les tenants de test
 * n'ont pas au moment où les migrations tournent. Sur une base réelle, la
 * deuxième campagne ouverte échouait en erreur interne.</p>
 *
 * <p>Un second garde-fou le remplace, qui traduit la règle telle qu'elle
 * est aujourd'hui : <strong>une seule campagne principale par année</strong>.
 * La validation applicative le dit déjà ; l'index le tient même si une
 * écriture passe à côté d'elle.</p>
 */
@ChangeUnit(id = "campaign_season_indexes", order = "073", author = "neiba")
public class M073_CampaignSeasonIndexes {

    private static final String CAMPAIGNS = "campaigns";
    private static final String OBSOLETE = "uniq_campaigns_open";
    private static final String MAIN_PER_YEAR = "uniq_campaigns_main_per_year";

    @Execution
    public void execute(MongoDatabase database) {
        var collection = database.getCollection(CAMPAIGNS);

        // Idempotence : l'index peut avoir déjà été retiré, ou n'avoir
        // jamais existé sur un tenant provisionné après le changement de
        // règle.
        for (Document index : collection.listIndexes()) {
            if (OBSOLETE.equals(index.getString("name"))) {
                collection.dropIndex(OBSOLETE);
            }
        }

        boolean alreadyThere = false;
        for (Document index : collection.listIndexes()) {
            if (MAIN_PER_YEAR.equals(index.getString("name"))) alreadyThere = true;
        }
        if (!alreadyThere) {
            collection.createIndex(
                    new Document("campaignYear", 1).append("kind", 1),
                    new IndexOptions()
                            .name(MAIN_PER_YEAR)
                            .unique(true)
                            // Seules les principales sont contraintes : les
                            // intermédiaires d'une année sont plusieurs.
                            .partialFilterExpression(new Document("kind", "MAIN")));
        }
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        var collection = database.getCollection(CAMPAIGNS);
        for (Document index : collection.listIndexes()) {
            if (MAIN_PER_YEAR.equals(index.getString("name"))) {
                collection.dropIndex(MAIN_PER_YEAR);
            }
        }
    }
}
