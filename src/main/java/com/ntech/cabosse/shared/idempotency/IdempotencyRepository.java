package com.ntech.cabosse.shared.idempotency;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Optional;

/**
 * Traces d'idempotence du tenant.
 *
 * <p>La réservation est une insertion : c'est l'unicité de la clé primaire
 * qui arbitre entre deux requêtes concurrentes portant la même clé, et non
 * une lecture suivie d'une écriture qui laisserait passer les deux.</p>
 */
@ApplicationScoped
public class IdempotencyRepository {

    public static final String COLLECTION = "idempotency_keys";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<IdempotencyRecordEntity> coll() {
        return tenantDb.collection(COLLECTION, IdempotencyRecordEntity.class);
    }

    public Optional<IdempotencyRecordEntity> find(String key) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", key)).first());
    }

    /**
     * Tente de réserver la clé. Rend {@code true} si la réservation est
     * acquise, {@code false} si un autre appel l'a déjà prise : dans ce
     * second cas, l'appelant doit rejouer la réponse enregistrée plutôt
     * que refaire le travail.
     */
    public boolean tryReserve(IdempotencyRecordEntity record) {
        try {
            coll().insertOne(record);
            return true;
        } catch (MongoWriteException e) {
            if (ErrorCategory.fromErrorCode(e.getError().getCode()) == ErrorCategory.DUPLICATE_KEY) {
                return false;
            }
            throw e;
        }
    }

    /** Enregistre la réponse rendue, une fois l'opération réellement traitée. */
    public void completeWith(String key, int statusCode, String responseBody) {
        coll().updateOne(Filters.eq("_id", key), Updates.combine(
                Updates.set("statusCode", statusCode),
                Updates.set("responseBody", responseBody)));
    }

    /**
     * Libère une réservation dont l'opération a échoué. Un refus métier ne
     * doit pas condamner la clé : l'utilisateur corrige sa saisie et
     * renvoie, souvent avec la même clé puisque c'est la même opération.
     */
    public void release(String key) {
        coll().deleteOne(Filters.eq("_id", key));
    }

    /** Trace périmée : la clé redevient utilisable. */
    public long purgeExpired(Instant now) {
        return coll().deleteMany(Filters.lte("expiresAt", now)).getDeletedCount();
    }
}
