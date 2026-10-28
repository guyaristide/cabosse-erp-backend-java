package com.ntech.cabosse.shared.trace;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.ntech.cabosse.shared.persistence.ControlPlane;
import com.ntech.cabosse.shared.persistence.ControlPlaneProvider;
import com.ntech.cabosse.shared.tenant.TenantStatus;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.bson.Document;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Tient l'index TTL des traces de requêtes aligné sur la configuration.
 *
 * <p>Pourquoi pas la migration : une unité Mongock est statique, elle ne
 * peut pas lire {@code cabosse.trace.retention-days}. La migration 095
 * pose l'index avec la valeur par défaut ; ce bean relit ensuite la valeur
 * configurée et, si elle diffère, ajuste l'index par {@code collMod} sans
 * le reconstruire. Même geste idempotent que
 * {@code ControlPlaneSchemaBootstrap} : rejouable à chaque démarrage.</p>
 *
 * <p>Deux moments : au démarrage pour toutes les structures vivantes, et
 * avant la première écriture dans une base que ce processus n'a pas encore
 * vue, pour couvrir une structure provisionnée après le démarrage ou une
 * base de test créée à la volée.</p>
 */
@ApplicationScoped
public class RequestTraceRetention {

    public static final String TTL_INDEX = "ttl_request_traces_occurredAt";

    private static final List<String> LIVE_STATUSES = List.of(
            TenantStatus.ACTIVE.name(), TenantStatus.SUSPENDED.name());

    @Inject TraceConfig config;
    @Inject ControlPlaneProvider controlPlane;
    @Inject MongoClient mongoClient;
    @Inject Logger log;

    /** Bases déjà alignées par ce processus : l'index ne se relit pas à chaque trace. */
    private final Set<String> ensured = ConcurrentHashMap.newKeySet();

    /** Après les migrations tenant (APPLICATION + 600), qui posent l'index initial. */
    void onStart(@Observes @Priority(jakarta.interceptor.Interceptor.Priority.APPLICATION + 700)
                 StartupEvent ev) {
        for (TenantEntity tenant : controlPlane
                .collection(ControlPlane.Collections.TENANTS, TenantEntity.class)
                .find(Filters.in("status", LIVE_STATUSES))) {
            if (tenant.databaseName == null || ControlPlane.DATABASE.equals(tenant.databaseName)) {
                continue;
            }
            ensure(mongoClient.getDatabase(tenant.databaseName));
        }
    }

    /** Aligne l'index de cette base, une fois par processus. Ne lève jamais. */
    public void ensure(MongoDatabase db) {
        if (db == null || !ensured.add(db.getName())) return;
        try {
            apply(db);
        } catch (Exception e) {
            // On retentera à la prochaine trace : un index TTL absent ne
            // bloque pas l'écriture, il laisse juste grossir la collection.
            ensured.remove(db.getName());
            log.warnf(e, "Index TTL des traces non aligné sur %s", db.getName());
        }
    }

    private void apply(MongoDatabase db) {
        long seconds = Duration.ofDays(config.retentionDays()).toSeconds();
        var collection = db.getCollection(RequestTraceEntity.COLLECTION);
        for (Document index : collection.listIndexes()) {
            if (!TTL_INDEX.equals(index.getString("name"))) continue;
            Number current = index.get("expireAfterSeconds", Number.class);
            if (current != null && current.longValue() == seconds) return;
            // Changer la durée ne reconstruit pas l'index : collMod le
            // modifie en place, ce qui reste bon marché sur deux ans de traces.
            db.runCommand(new Document("collMod", RequestTraceEntity.COLLECTION)
                    .append("index", new Document("name", TTL_INDEX)
                            .append("expireAfterSeconds", seconds)));
            log.infof("Rétention des traces de %s portée à %d jours",
                    db.getName(), config.retentionDays());
            return;
        }
        collection.createIndex(Indexes.ascending("occurredAt"),
                new IndexOptions().name(TTL_INDEX).expireAfter(seconds, TimeUnit.SECONDS));
    }
}
