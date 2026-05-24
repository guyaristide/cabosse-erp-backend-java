package com.ntech.cabosse.stock.service;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.time.Year;

/**
 * Génère les références séquentielles {@code MV-YYYY-NNNNNN} pour les
 * mouvements de stock. Compteur partagé avec les autres
 * (collection {@code counters}, clé {@code stock_movement:YYYY}).
 *
 * <p>Volume attendu beaucoup plus grand que les BC/RD (chaque ligne
 * réceptionnée, chaque consommation production, chaque vente génère un
 * mouvement) — d'où la largeur 6 chiffres.</p>
 */
@ApplicationScoped
public class StockMovementRefService {

    private static final String COLLECTION = "counters";
    private static final String KEY_PREFIX = "stock_movement:";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<Document> coll() {
        return tenantDb.database().getCollection(COLLECTION);
    }

    public String next() {
        int year = Year.now().getValue();
        String key = KEY_PREFIX + year;
        Document updated = coll().findOneAndUpdate(
                Filters.eq("_id", key),
                Updates.inc("seq", 1L),
                new FindOneAndUpdateOptions()
                        .upsert(true)
                        .returnDocument(ReturnDocument.AFTER)
        );
        long seq = updated != null ? updated.getLong("seq") : 1L;
        return String.format("MV-%d-%06d", year, seq);
    }
}
