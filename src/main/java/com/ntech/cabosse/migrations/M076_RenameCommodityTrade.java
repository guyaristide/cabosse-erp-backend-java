package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Updates;
import com.ntech.cabosse.shared.persistence.ControlPlane;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;

/**
 * Migration 076 — le négoce cesse de s'appeler cacao.
 *
 * <p>La règle du produit interdit qu'une filière donne son nom à un
 * package, une classe, une route ou une collection : la spécificité vit en
 * valeur de configuration. Le domaine du négoce la violait partout, alors
 * qu'il est agnostique par construction — la capacité qui le gouverne
 * s'appelle d'ailleurs déjà {@code HAS_COMMODITY_TRADE}. Un négociant
 * d'anacarde y vendait sa marchandise depuis une collection
 * {@code cacao_sales}.</p>
 *
 * <p>Trois natures de données sont reprises, et la troisième est la seule
 * qui compte vraiment :</p>
 *
 * <ul>
 *   <li>la <strong>collection</strong> des ventes et ses index nommés ;</li>
 *   <li>la <strong>clé de compteur</strong> des références, sans quoi la
 *       numérotation repartirait à un et produirait des doublons ;</li>
 *   <li>trois <strong>valeurs d'énumération persistées hors du domaine</strong> :
 *       la source d'un mouvement de stock, l'origine d'une pièce comptable
 *       et le type d'un événement d'audit. Laissées en l'état, elles ne se
 *       relisent plus : une valeur inconnue d'une énumération fait échouer
 *       la désérialisation, et c'est tout l'historique de stock et de
 *       comptabilité qui devient illisible.</li>
 * </ul>
 *
 * <p>L'audit vit dans le plan de contrôle, partagé par tous les tenants :
 * il est repris une fois, sur les seules lignes du tenant courant, comme
 * le fait déjà la migration des profils.</p>
 */
@ChangeUnit(id = "rename_commodity_trade", order = "076", author = "neiba")
public class M076_RenameCommodityTrade {

    private static final String OLD_SALES = "cacao_sales";
    private static final String NEW_SALES = "commodity_sales";

    @Execution
    public void execute(MongoDatabase database, MongoClient client) {
        renameCollection(database);
        renameCounter(database);
        renameEnumValue(database, "stock_movements", "source", "CACAO_SALE", "COMMODITY_SALE");
        renameEnumValue(database, "journal_pieces", "sourceType", "CACAO_SALE", "COMMODITY_SALE");
        renameAuditEvents(database, client);
        renamePreference(database, client);
    }

    /**
     * Renomme la collection, puis repose ses index sous leur nouveau nom.
     *
     * <p>Un {@code renameCollection} conserve les index avec leurs anciens
     * noms : les laisser laisserait « cacao » dans la base sans que rien ne
     * le montre.</p>
     */
    private void renameCollection(MongoDatabase database) {
        boolean oldExists = false;
        boolean newExists = false;
        for (String name : database.listCollectionNames()) {
            if (OLD_SALES.equals(name)) oldExists = true;
            if (NEW_SALES.equals(name)) newExists = true;
        }
        if (!oldExists) return;

        if (newExists) {
            // Reprise partielle interrompue : on verse le reliquat plutôt
            // que d'échouer sur un nom déjà pris.
            var from = database.getCollection(OLD_SALES);
            var to = database.getCollection(NEW_SALES);
            for (Document doc : from.find()) {
                to.replaceOne(Filters.eq("_id", doc.get("_id")), doc,
                        new com.mongodb.client.model.ReplaceOptions().upsert(true));
            }
            from.drop();
        } else {
            database.getCollection(OLD_SALES).renameCollection(
                    new com.mongodb.MongoNamespace(database.getName(), NEW_SALES));
        }

        var sales = database.getCollection(NEW_SALES);
        for (Document index : sales.listIndexes()) {
            String name = index.getString("name");
            if (name != null && name.contains("cacao")) sales.dropIndex(name);
        }
        sales.createIndex(new Document("ref", 1),
                new IndexOptions().unique(true).name("uniq_commodity_sales_ref"));
        sales.createIndex(new Document("campaignYear", 1),
                new IndexOptions().name("idx_commodity_sales_campaign"));
        sales.createIndex(new Document("customerId", 1),
                new IndexOptions().name("idx_commodity_sales_customer"));
        sales.createIndex(new Document("date", -1),
                new IndexOptions().name("idx_commodity_sales_date"));
    }

    /**
     * La séquence des références suit son domaine.
     *
     * <p>Sans reprise, le compteur repartirait de zéro et la référence
     * suivante entrerait en collision avec une vente existante, que l'index
     * d'unicité refuserait.</p>
     */
    private void renameCounter(MongoDatabase database) {
        var counters = database.getCollection("counters");
        for (Document counter : counters.find()) {
            Object id = counter.get("_id");
            if (!(id instanceof String key) || !key.startsWith("cacao_sale")) continue;
            String renamed = "commodity_sale" + key.substring("cacao_sale".length());
            Document moved = new Document(counter);
            moved.put("_id", renamed);
            counters.replaceOne(Filters.eq("_id", renamed), moved,
                    new com.mongodb.client.model.ReplaceOptions().upsert(true));
            counters.deleteOne(Filters.eq("_id", key));
        }
    }

    /** Une valeur d'énumération persistée, réécrite là où elle est stockée. */
    private void renameEnumValue(MongoDatabase database, String collection,
                                 String field, String from, String to) {
        database.getCollection(collection).updateMany(
                Filters.eq(field, from), Updates.set(field, to));
    }

    /**
     * L'audit est partagé : on ne réécrit que les lignes de ce tenant.
     *
     * <p>Réécrire tout le plan de contrôle depuis la migration d'un tenant
     * le ferait autant de fois qu'il y a de tenants, et toucherait des
     * lignes qui ne lui appartiennent pas.</p>
     */
    private void renameAuditEvents(MongoDatabase database, MongoClient client) {
        MongoDatabase control = client.getDatabase(ControlPlane.DATABASE);
        Document tenant = control.getCollection(ControlPlane.Collections.TENANTS)
                .find(Filters.eq("databaseName", database.getName())).first();
        if (tenant == null) return;

        control.getCollection(ControlPlane.Collections.GLOBAL_AUDIT).updateMany(
                Filters.and(
                        Filters.eq("tenantId", tenant.get("_id")),
                        Filters.eq("eventType", "CACAO_SALE_CREATED")),
                Updates.set("eventType", "COMMODITY_SALE_CREATED"));

        control.getCollection(ControlPlane.Collections.GLOBAL_AUDIT).updateMany(
                Filters.and(
                        Filters.eq("tenantId", tenant.get("_id")),
                        Filters.eq("targetType", "cacao_sale")),
                Updates.set("targetType", "commodity_sale"));
    }

    /** Le taux de TVA de la vente suit le même renommage. */
    private void renamePreference(MongoDatabase database, MongoClient client) {
        MongoDatabase control = client.getDatabase(ControlPlane.DATABASE);
        var tenants = control.getCollection(ControlPlane.Collections.TENANTS);
        Document tenant = tenants.find(Filters.eq("databaseName", database.getName())).first();
        if (tenant == null) return;
        tenants.updateOne(
                Filters.and(Filters.eq("_id", tenant.get("_id")),
                        Filters.exists("preferences.cacaoSaleVatRatePct", true)),
                Updates.rename("preferences.cacaoSaleVatRatePct", "preferences.commoditySaleVatRatePct"));
    }

    /**
     * Pas de retour en arrière.
     *
     * <p>Défaire ce renommage remettrait un vocabulaire de filière dans un
     * domaine agnostique, et les documents écrits depuis la migration
     * porteraient déjà le nouveau nom : le retour serait partiel, donc
     * faux.</p>
     */
    @RollbackExecution
    public void rollback(MongoDatabase database) {
        // Volontairement vide.
    }
}
