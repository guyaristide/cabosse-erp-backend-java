package com.ntech.cabosse.shared.trace;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.time.Instant;

/**
 * Traces de requêtes d'une structure.
 *
 * <p>Chaque méthode existe en deux formes : sur la structure de la requête
 * en cours, et sur une base nommée. La seconde sert au filtre, qui écrit
 * hors du fil de la requête quand il y est contraint, et à l'archive des
 * journaux ouverte par l'éditeur sur une autre structure que la sienne,
 * comme le fait déjà le diagnostic.</p>
 */
@ApplicationScoped
public class RequestTraceRepository {

    @Inject MongoClient mongoClient;
    @Inject TenantMongoDatabaseProvider tenantDb;
    @Inject RequestTraceRetention retention;

    public void insert(RequestTraceEntity trace) {
        insert(tenantDb.database().getName(), trace);
    }

    public void insert(String databaseName, RequestTraceEntity trace) {
        MongoDatabase db = mongoClient.getDatabase(databaseName);
        retention.ensure(db);
        db.getCollection(RequestTraceEntity.COLLECTION, RequestTraceEntity.class).insertOne(trace);
    }

    /** Les traces de la structure courante sur l'intervalle, de la plus ancienne à la plus récente. */
    public FindIterable<Document> list(Instant from, Instant to) {
        return list(tenantDb.database().getName(), from, to);
    }

    /**
     * Documents bruts plutôt qu'entités : l'archive les écrit tels quels,
     * et un champ ajouté demain y figurera sans qu'on y pense.
     */
    public FindIterable<Document> list(String databaseName, Instant from, Instant to) {
        return mongoClient.getDatabase(databaseName)
                .getCollection(RequestTraceEntity.COLLECTION)
                .find(Filters.and(
                        Filters.gte("occurredAt", from),
                        Filters.lte("occurredAt", to)))
                .sort(Sorts.ascending("occurredAt"));
    }
}
