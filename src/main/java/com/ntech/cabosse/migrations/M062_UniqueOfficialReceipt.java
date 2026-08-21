package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Migration 062 — un reçu officiel ne couvre qu'une opération.
 *
 * <p>Le numéro de reçu officiel saisi sur les achats producteurs n'avait
 * aucune contrainte d'unicité : un même numéro pouvait couvrir deux
 * livraisons, ce qui permet de masquer un détournement derrière un reçu
 * réutilisé (audit anti-fraude du 11/08/2026, ticket CE-28).</p>
 *
 * <p>Index unique partiel : seuls les documents portant réellement un
 * numéro sont contraints, un champ absent ou nul reste libre. Si des
 * doublons préexistent chez un tenant, l'unicité est impossible : on pose
 * l'index en simple accélérateur et on signale les numéros en cause, le
 * contrôle applicatif bloquant de toute façon les nouveaux doublons. Ne
 * jamais poser cet index en {@code unique} sans ce contrôle : l'échec
 * abandonnerait toute la chaîne de migrations du tenant.</p>
 */
@ChangeUnit(id = "unique_official_receipt", order = "062", author = "neiba")
public class M062_UniqueOfficialReceipt {

    private static final Logger LOG = Logger.getLogger(M062_UniqueOfficialReceipt.class);

    static final String INDEX_NAME = "uniq_producer_purchases_officialReceiptRef";

    @Execution
    public void execute(MongoDatabase database) {
        var purchases = database.getCollection("producer_purchases");

        for (Document index : purchases.listIndexes()) {
            if (INDEX_NAME.equals(index.getString("name"))) return;
        }

        boolean hasDuplicates = hasDuplicateReceipts(database);
        purchases.createIndex(Indexes.ascending("officialReceiptRef"),
                officialReceiptIndexOptions(hasDuplicates));
        LOG.infof("M062 : index du reçu officiel posé (unique=%s)", !hasDuplicates);
    }

    static IndexOptions officialReceiptIndexOptions(boolean hasDuplicates) {
        Bson onlyWithReceipt = Filters.type("officialReceiptRef", "string");
        return new IndexOptions()
                .name(INDEX_NAME)
                .unique(!hasDuplicates)
                .partialFilterExpression(onlyWithReceipt);
    }

    /** Deux livraisons sous le même numéro interdisent l'unicité. */
    private static boolean hasDuplicateReceipts(MongoDatabase database) {
        var duplicates = database.getCollection("producer_purchases").aggregate(List.of(
                new Document("$match", new Document("officialReceiptRef",
                        new Document("$type", "string"))),
                new Document("$group", new Document("_id", "$officialReceiptRef")
                        .append("count", new Document("$sum", 1))),
                new Document("$match", new Document("count", new Document("$gt", 1))),
                new Document("$limit", 5)));
        boolean found = false;
        for (Document d : duplicates) {
            found = true;
            LOG.warnf("M062 : reçu officiel en doublon « %s », unicité non posée sur ce tenant",
                    d.get("_id"));
        }
        return found;
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        // L'index se retire sans perte : les données restent intactes.
        try {
            database.getCollection("producer_purchases").dropIndex(INDEX_NAME);
        } catch (RuntimeException ignored) {
            // Index absent : rien à défaire.
        }
    }
}
