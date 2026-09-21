package com.ntech.cabosse.delegatestatus.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.delegatestatus.entity.DelegateStatusPositionEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Accès à l'historique des positions.
 *
 * <p>Collection en ajout seul : aucune méthode de modification ni de
 * suppression n'est offerte, et c'est voulu. Corriger une position se fait
 * en en posant une nouvelle, ce qui laisse la trace de la correction.</p>
 */
@ApplicationScoped
public class DelegateStatusPositionRepository {

    public static final String COLLECTION = "delegate_status_positions";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<DelegateStatusPositionEntity> coll() {
        return tenantDb.collection(COLLECTION, DelegateStatusPositionEntity.class);
    }

    public void insert(DelegateStatusPositionEntity e) {
        coll().insertOne(e);
    }

    /** Historique d'un délégué, de la position la plus récente à la plus ancienne. */
    public List<DelegateStatusPositionEntity> history(UUID delegateSupplierId) {
        return coll()
                .find(Filters.eq("delegateSupplierId", delegateSupplierId))
                .sort(new Document("effectiveDate", -1).append("createdAt", -1))
                .into(new ArrayList<>());
    }

    /**
     * Position courante de chaque délégué, en une seule lecture.
     *
     * <p>L'état des délégués affiche une ligne par délégué : une requête
     * par ligne ferait autant d'allers-retours que la coopérative a de
     * collecteurs, ce que le projet a déjà corrigé ailleurs.</p>
     */
    public Map<UUID, DelegateStatusPositionEntity> currentByDelegate() {
        Map<UUID, DelegateStatusPositionEntity> current = new HashMap<>();
        Comparator<DelegateStatusPositionEntity> order =
                Comparator.comparing((DelegateStatusPositionEntity p) -> p.effectiveDate)
                        .thenComparing(p -> p.createdAt);
        for (DelegateStatusPositionEntity p : coll().find().into(new ArrayList<>())) {
            if (p.delegateSupplierId == null || p.effectiveDate == null) continue;
            DelegateStatusPositionEntity kept = current.get(p.delegateSupplierId);
            if (kept == null || order.compare(p, kept) > 0) {
                current.put(p.delegateSupplierId, p);
            }
        }
        return current;
    }

    public long countForDelegate(UUID delegateSupplierId) {
        return coll().countDocuments(Filters.eq("delegateSupplierId", delegateSupplierId));
    }
}
