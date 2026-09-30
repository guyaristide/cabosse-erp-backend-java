package com.ntech.cabosse.members.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.members.entity.MemberEntity;
import com.ntech.cabosse.members.entity.MemberStatus;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Accès aux membres-producteurs de la structure (tenant-scoped). */
@ApplicationScoped
public class MemberRepository {

    public static final String COLLECTION = "members";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<MemberEntity> coll() {
        return tenantDb.collection(COLLECTION, MemberEntity.class);
    }

    public Optional<MemberEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    /** Tous les membres du tenant, triés par nom (registre producteurs, REG-01). */
    public List<MemberEntity> listAll() {
        return coll().find().sort(new Document("name", 1)).into(new java.util.ArrayList<>());
    }

    public boolean codeExists(String code) {
        return coll().countDocuments(Filters.eq("code", code)) > 0;
    }

    public Optional<MemberEntity> findBySupplierId(UUID supplierId) {
        return Optional.ofNullable(coll().find(Filters.eq("supplierId", supplierId)).first());
    }

    public long countSearch(String q, MemberStatus statusFilter) {
        return countSearch(new MemberSearchCriteria(q, statusFilter, null, null, null, null));
    }

    public List<MemberEntity> search(String q, MemberStatus statusFilter, int skip, int limit) {
        return search(new MemberSearchCriteria(q, statusFilter, null, null, null, null),
                skip, limit);
    }

    public long countSearch(MemberSearchCriteria criteria) {
        return coll().countDocuments(searchFilter(criteria));
    }

    public List<MemberEntity> search(MemberSearchCriteria criteria, int skip, int limit) {
        return coll().find(searchFilter(criteria))
                .sort(new Document("name", 1))
                .skip(skip)
                .limit(limit)
                .into(new ArrayList<>());
    }

    /**
     * Les villages réellement portés par des fiches.
     *
     * <p>Le village est une saisie libre : proposer un référentiel
     * n'aurait pas les mêmes valeurs que les fiches, et le filtre
     * rendrait des listes vides sur des noms pourtant présents.</p>
     */
    public List<String> distinctVillages() {
        List<String> out = new ArrayList<>();
        coll().distinct("village", String.class).forEach(v -> {
            if (v != null && !v.isBlank()) out.add(v);
        });
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private static Bson searchFilter(MemberSearchCriteria c) {
        List<Bson> filters = new ArrayList<>();
        if (c.status() != null) filters.add(Filters.eq("status", c.status().name()));
        // Faux se dit aussi des fiches anciennes où le champ n'existe
        // pas : sans ce « ou absent », les producteurs d'avant la
        // livraison du drapeau disparaîtraient du filtre.
        if (Boolean.TRUE.equals(c.collector())) {
            filters.add(Filters.eq("collector", true));
        } else if (Boolean.FALSE.equals(c.collector())) {
            filters.add(Filters.or(
                    Filters.eq("collector", false),
                    Filters.exists("collector", false)));
        }
        if (c.sectionId() != null) filters.add(Filters.eq("sectionId", c.sectionId()));
        if (c.village() != null && !c.village().isBlank()) {
            filters.add(Filters.regex("village",
                    "^" + java.util.regex.Pattern.quote(c.village().trim()) + "$", "i"));
        }
        if (c.gender() != null) filters.add(Filters.eq("gender", c.gender().name()));
        if (c.q() != null && !c.q().isBlank()) {
            String escaped = java.util.regex.Pattern.quote(c.q().trim());
            filters.add(Filters.or(
                    Filters.regex("code", escaped, "i"),
                    Filters.regex("name", escaped, "i"),
                    Filters.regex("village", escaped, "i"),
                    Filters.regex("phone", escaped, "i")
            ));
        }
        return filters.isEmpty() ? new Document() : Filters.and(filters);
    }

    public long count() {
        return coll().countDocuments();
    }

    /** Producteurs portant ce numéro normalisé. Doit rester vide ou singleton. */
    public List<MemberEntity> findByProducerRefKey(String normalizedNumber) {
        if (normalizedNumber == null || normalizedNumber.isBlank()) return List.of();
        return coll().find(Filters.eq("producerRefKeys", normalizedNumber))
                .into(new java.util.ArrayList<>());
    }

    /** Producteurs portant au moins une pièce de ce type. */
    public List<MemberEntity> findByDocumentType(String typeName) {
        if (typeName == null || typeName.isBlank()) return List.of();
        return coll().find(Filters.eq("identityDocuments.type", typeName))
                .into(new java.util.ArrayList<>());
    }

    /** Écriture ciblée des clés de rapprochement (resynchronisation d'un type). */
    public void updateProducerRefKeys(MemberEntity e) {
        coll().updateOne(Filters.eq("_id", e.id),
                com.mongodb.client.model.Updates.combine(
                        com.mongodb.client.model.Updates.set("producerRefKeys", e.producerRefKeys),
                        com.mongodb.client.model.Updates.set("identityDocuments", e.identityDocuments)));
    }

    public void insert(MemberEntity e) { coll().insertOne(e); }

    public void replace(MemberEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }
    /** Le producteur qui porte déjà ce compte d'avance, s'il en existe un. */
    public java.util.Optional<MemberEntity> findByAdvanceAccount(String account) {
        if (account == null || account.isBlank()) return java.util.Optional.empty();
        return java.util.Optional.ofNullable(
                coll().find(com.mongodb.client.model.Filters.eq("advanceAccount", account.trim()))
                        .first());
    }

    /**
     * Supprime définitivement une fiche.
     *
     * <p>Le produit radie, il ne supprime pas : une fiche porte des faits
     * qui lui survivent. La seule exception est l'annulation d'un import,
     * qui défait des fiches créées par erreur et qui n'ont rien produit —
     * le service qui l'appelle vérifie d'abord qu'aucune livraison,
     * aucun crédit ni aucune écriture ne s'y rattache. Ne pas employer
     * ailleurs : radier est ce qu'il faut partout ailleurs.</p>
     */
    public void deleteById(UUID id) {
        coll().deleteOne(com.mongodb.client.model.Filters.eq("_id", id));
    }
}
