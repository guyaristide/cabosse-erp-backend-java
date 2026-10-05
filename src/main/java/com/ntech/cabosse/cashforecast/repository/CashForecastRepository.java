package com.ntech.cabosse.cashforecast.repository;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.ntech.cabosse.cashforecast.entity.CashForecastEntity;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import com.mongodb.client.MongoCollection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Les prévisionnels de décaissement d'une structure. */
@ApplicationScoped
public class CashForecastRepository {

    public static final String COLLECTION = "cash_forecasts";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<CashForecastEntity> coll() {
        return tenantDb.collection(COLLECTION, CashForecastEntity.class);
    }

    public Optional<CashForecastEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    /** Celui d'un mois donné, s'il existe : il n'y en a jamais deux. */
    public Optional<CashForecastEntity> findByMonth(String month) {
        return Optional.ofNullable(coll().find(Filters.eq("month", month)).first());
    }

    /** Du plus récent au plus ancien : c'est le mois qui vient qu'on cherche. */
    public List<CashForecastEntity> listAll() {
        return coll().find().sort(Sorts.descending("month")).into(new ArrayList<>());
    }

    public void insert(CashForecastEntity e) {
        coll().insertOne(e);
    }

    /**
     * Remplace, en refusant d'écraser une version plus récente.
     *
     * <p>Le directeur corrige pendant que le conseil lit : sans ce
     * contrôle, la décision porterait sur un document que la
     * sauvegarde suivante effacerait.</p>
     */
    public void replace(CashForecastEntity e) {
        long expected = e.version;
        e.version = expected + 1;
        var result = coll().replaceOne(
                Filters.and(Filters.eq("_id", e.id), Filters.eq("version", expected)), e);
        if (result.getMatchedCount() == 0) {
            throw new ConflictException(Messages.msg("m.prev-concurrent-update"));
        }
    }

    public void deleteById(UUID id) {
        coll().deleteOne(Filters.eq("_id", id));
    }
}
