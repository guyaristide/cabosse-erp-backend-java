package com.ntech.cabosse.article.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.article.entity.ArticleEntity;
import com.ntech.cabosse.article.entity.ArticleType;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ArticleRepository {

    public static final String COLLECTION = "articles";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<ArticleEntity> coll() {
        return tenantDb.collection(COLLECTION, ArticleEntity.class);
    }

    public List<ArticleEntity> listAll() {
        return coll().find()
                .sort(new org.bson.Document("type", 1).append("name", 1))
                .into(new ArrayList<>());
    }

    public List<ArticleEntity> listByType(ArticleType type) {
        return coll().find(Filters.eq("type", type.name()))
                .sort(new org.bson.Document("name", 1))
                .into(new ArrayList<>());
    }

    public Optional<ArticleEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public boolean codeExists(String code) {
        return coll().countDocuments(Filters.eq("code", code)) > 0;
    }

    /**
     * Recherche par nom <em>case-insensitive</em> (et optionnellement
     * restreinte à un type). Le couple (name, type) permet de distinguer
     * un article "Lait" matière première d'un éventuel produit fini de
     * même nom. {@code type} {@code null} = pas de filtre.
     */
    public Optional<ArticleEntity> findByName(String name, ArticleType type) {
        if (name == null || name.isBlank()) return Optional.empty();
        String regex = "^" + java.util.regex.Pattern.quote(name.trim()) + "$";
        var filter = type == null
                ? Filters.regex("name", regex, "i")
                : Filters.and(
                        Filters.regex("name", regex, "i"),
                        Filters.eq("type", type.name()));
        return Optional.ofNullable(coll().find(filter).first());
    }

    public void insert(ArticleEntity e) {
        coll().insertOne(e);
    }

    public void replace(ArticleEntity e) {
        coll().replaceOne(Filters.eq("_id", e.id), e);
    }

    public void updateActive(UUID id, boolean active) {
        coll().updateOne(
                Filters.eq("_id", id),
                new org.bson.Document("$set", new org.bson.Document()
                        .append("active", active)
                        .append("updatedAt", Instant.now()))
        );
    }
}
