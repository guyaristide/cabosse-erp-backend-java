package com.ntech.cabosse.site.repository;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import com.ntech.cabosse.site.entity.SiteEntity;
import com.ntech.cabosse.site.entity.SiteType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Accès aux sites du tenant courant.
 *
 * <p>Lit la base via {@link TenantMongoDatabaseProvider} → le scope est
 * garanti par le {@link com.ntech.cabosse.shared.tenant.TenantContext}
 * hydraté en amont (filtre JAX-RS). Une requête sans contexte tenant
 * échouera explicitement à la résolution de la base.</p>
 *
 * <p>Pour les écritures hors HTTP (provisioning du tenant — pas de
 * contexte tenant initialisé), utiliser {@link #withDatabase(String)}.</p>
 */
@ApplicationScoped
public class SiteRepository {

    public static final String COLLECTION = "sites";

    @Inject TenantMongoDatabaseProvider tenantDb;
    @Inject MongoClient mongoClient;

    private MongoCollection<SiteEntity> sites() {
        return tenantDb.collection(COLLECTION, SiteEntity.class);
    }

    public List<SiteEntity> listAll() {
        return sites().find()
                .sort(new org.bson.Document("type", 1).append("name", 1))
                .into(new ArrayList<>());
    }

    public List<SiteEntity> listByType(SiteType type) {
        return sites().find(Filters.eq("type", type.name()))
                .sort(new org.bson.Document("name", 1))
                .into(new ArrayList<>());
    }

    public Optional<SiteEntity> findById(UUID id) {
        return Optional.ofNullable(sites().find(Filters.eq("_id", id)).first());
    }

    public boolean codeExists(String code) {
        return sites().countDocuments(Filters.eq("code", code)) > 0;
    }

    /**
     * Recherche par nom <em>case-insensitive</em>. Utilisé par l'import
     * vente pour résoudre le site depuis le libellé brut du fichier
     * (ex : « Méagui »). Trim implicite côté appelant.
     */
    public Optional<SiteEntity> findByName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String regex = "^" + java.util.regex.Pattern.quote(name.trim()) + "$";
        return Optional.ofNullable(
                sites().find(Filters.regex("name", regex, "i")).first());
    }

    public long count() {
        return sites().countDocuments();
    }

    public long countByType(SiteType type) {
        return sites().countDocuments(Filters.eq("type", type.name()));
    }

    public void insert(SiteEntity site) {
        sites().insertOne(site);
    }

    public void replace(SiteEntity site) {
        sites().replaceOne(Filters.eq("_id", site.id), site);
    }

    public void updateActive(UUID id, boolean active) {
        sites().updateOne(
                Filters.eq("_id", id),
                new org.bson.Document("$set", new org.bson.Document()
                        .append("active", active)
                        .append("updatedAt", java.time.Instant.now()))
        );
    }

    // ─── Écritures hors HTTP (provisioning) ─────────────────────────

    /**
     * Renvoie un repository pointant explicitement sur une base donnée,
     * sans passer par {@link TenantMongoDatabaseProvider}. Utilisé par
     * {@code TenantProvisioningService} qui doit insérer un site juste
     * après avoir créé la base, en dehors de tout contexte HTTP.
     */
    public DirectAccess withDatabase(String databaseName) {
        return new DirectAccess(mongoClient.getDatabase(databaseName)
                .getCollection(COLLECTION, SiteEntity.class));
    }

    public static final class DirectAccess {
        private final MongoCollection<SiteEntity> collection;

        DirectAccess(MongoCollection<SiteEntity> collection) {
            this.collection = collection;
        }

        public void insert(SiteEntity site) {
            collection.insertOne(site);
        }
    }

    @SuppressWarnings("unused")
    private static Bson typeEq(SiteType type) {
        return Filters.eq("type", type.name());
    }
}
