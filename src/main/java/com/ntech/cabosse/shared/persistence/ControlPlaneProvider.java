package com.ntech.cabosse.shared.persistence;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Provider d'accès au plan contrôle ({@code cabosse_control}).
 *
 * <p>Aucun service métier ne doit injecter {@link MongoClient}
 * directement — toujours passer par ce provider ou par
 * {@link TenantMongoDatabaseProvider}.</p>
 *
 * <p>Usage typique : services d'authentification, gestion des tenants
 * par M9, journal d'audit global.</p>
 */
@ApplicationScoped
public class ControlPlaneProvider {

    @Inject
    MongoClient mongoClient;

    public MongoDatabase database() {
        return mongoClient.getDatabase(ControlPlane.DATABASE);
    }

    public <T> MongoCollection<T> collection(String name, Class<T> type) {
        return database().getCollection(name, type);
    }
}
