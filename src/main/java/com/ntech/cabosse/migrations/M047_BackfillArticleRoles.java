package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;

import java.util.List;

/**
 * Migration 047 — rôles achetable / vendable sur les articles.
 *
 * <p>Introduit les deux drapeaux de rôle et les initialise selon la nature de
 * l'article : tout est <em>achetable</em> par défaut sauf les produits finis,
 * et seuls les produits finis sont <em>vendables</em> par défaut. Ces défauts
 * sont éditables ; un négociant cochera « vendable » sur sa matière première
 * (cacao brut) pour la revendre à l'export.</p>
 *
 * <p>La couche « produits vendus » du profil (ancien {@code TenantProduct})
 * est supprimée du modèle : les flux d'achat producteur, de vente cacao et le
 * « produit livré » du membre lisent désormais directement les articles
 * filtrés par rôle. Le champ membre {@code deliveredProductCodes} (codes
 * produit-coop) est retiré et remplacé par {@code deliveredArticleIds}
 * (références articles), réinitialisé vide — à ressaisir depuis les articles
 * achetables.</p>
 */
@ChangeUnit(id = "backfill_article_roles", order = "047", author = "neiba")
public class M047_BackfillArticleRoles {

    @Execution
    public void execute(MongoDatabase database) {
        // Drapeaux de rôle sur les articles qui ne les ont pas encore.
        database.getCollection("articles").updateMany(
                Filters.exists("purchasable", false),
                List.of(new Document("$set", new Document()
                        .append("purchasable", new Document("$ne", List.of("$type", "FINISHED_PRODUCT")))
                        .append("sellable", new Document("$eq", List.of("$type", "FINISHED_PRODUCT")))))
        );

        // Bascule du « produit livré » membre : ancien champ (codes produit-coop)
        // retiré, nouveau champ (références articles) initialisé vide.
        database.getCollection("members").updateMany(
                new Document(),
                List.of(
                        new Document("$set", new Document("deliveredArticleIds", List.of())),
                        new Document("$unset", "deliveredProductCodes"))
        );
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("articles").updateMany(
                new Document(),
                Updates.combine(Updates.unset("purchasable"), Updates.unset("sellable"))
        );
        database.getCollection("members").updateMany(
                new Document(),
                Updates.unset("deliveredArticleIds")
        );
    }
}
