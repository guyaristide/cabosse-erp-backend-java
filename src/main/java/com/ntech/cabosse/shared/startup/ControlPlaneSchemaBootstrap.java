package com.ntech.cabosse.shared.startup;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.ntech.cabosse.shared.persistence.ControlPlane;
import com.ntech.cabosse.shared.persistence.ControlPlaneProvider;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.bson.Document;
import org.jboss.logging.Logger;

/**
 * Crée les indexes du plan contrôle au démarrage de l'application.
 *
 * <p>Pourquoi pas Mongock pour le control plane : le schéma du control
 * plane est petit et évolue rarement. Un observer {@code StartupEvent}
 * idempotent (Mongo n'erreure pas si on recrée un index identique) suffit.
 * Mongock reste utilisé pour les bases tenant qui ont des migrations
 * potentiellement nombreuses et coordonnées.</p>
 *
 * <p>Tous les {@code createIndex} sont idempotents : si l'index existe
 * avec la même spec, c'est un no-op. Si la spec diffère, Mongo lève une
 * erreur — c'est volontaire (changement d'index → migration explicite).</p>
 */
@ApplicationScoped
public class ControlPlaneSchemaBootstrap {

    @Inject
    ControlPlaneProvider controlPlane;

    @Inject
    Logger log;

    void onStart(@Observes StartupEvent ev) {
        log.info("Control plane schema bootstrap : start");

        // ─── tenants ───
        controlPlane.collection(ControlPlane.Collections.TENANTS, Document.class)
                .createIndex(
                        Indexes.ascending("slug"),
                        new IndexOptions().unique(true).name("uniq_tenants_slug")
                );
        controlPlane.collection(ControlPlane.Collections.TENANTS, Document.class)
                .createIndex(
                        Indexes.ascending("status"),
                        new IndexOptions().name("idx_tenants_status")
                );
        controlPlane.collection(ControlPlane.Collections.TENANTS, Document.class)
                .createIndex(
                        Indexes.descending("createdAt"),
                        new IndexOptions().name("idx_tenants_createdAt_desc")
                );

        // ─── users ───
        controlPlane.collection(ControlPlane.Collections.USERS, Document.class)
                .createIndex(
                        Indexes.ascending("email"),
                        new IndexOptions().unique(true).name("uniq_users_email")
                );
        controlPlane.collection(ControlPlane.Collections.USERS, Document.class)
                .createIndex(
                        Indexes.ascending("tenantId"),
                        new IndexOptions().name("idx_users_tenantId")
                );

        // ─── support tickets ───
        controlPlane.collection(ControlPlane.Collections.SUPPORT_TICKETS, Document.class)
                .createIndex(
                        Indexes.ascending("tenantId"),
                        new IndexOptions().name("idx_tickets_tenantId")
                );
        controlPlane.collection(ControlPlane.Collections.SUPPORT_TICKETS, Document.class)
                .createIndex(
                        Indexes.compoundIndex(
                                Indexes.ascending("status"),
                                Indexes.ascending("priority")
                        ),
                        new IndexOptions().name("idx_tickets_status_priority")
                );

        // ─── global audit ───
        controlPlane.collection(ControlPlane.Collections.GLOBAL_AUDIT, Document.class)
                .createIndex(
                        Indexes.descending("occurredAt"),
                        new IndexOptions().name("idx_audit_occurredAt_desc")
                );
        controlPlane.collection(ControlPlane.Collections.GLOBAL_AUDIT, Document.class)
                .createIndex(
                        Indexes.ascending("tenantId"),
                        new IndexOptions().name("idx_audit_tenantId")
                );

        // ─── tenant backups ───
        // Lu par TenantTechnicalService (statut technique d'un tenant) :
        // filtre par tenantId + tri executedAt desc.
        controlPlane.collection(ControlPlane.Collections.TENANT_BACKUPS, Document.class)
                .createIndex(
                        Indexes.compoundIndex(
                                Indexes.ascending("tenantId"),
                                Indexes.descending("executedAt")
                        ),
                        new IndexOptions().name("idx_backups_tenantId_executedAt")
                );

        // ─── catalogues partagés ───
        // countries : _id = code ISO-2 (clé naturelle), pas d'index supplémentaire
        // mais on indexe isActive pour les filtres rapides côté API.
        controlPlane.collection(ControlPlane.Collections.COUNTRIES, Document.class)
                .createIndex(
                        Indexes.ascending("isActive"),
                        new IndexOptions().name("idx_countries_isActive")
                );

        controlPlane.collection(ControlPlane.Collections.REGIONS, Document.class)
                .createIndex(
                        Indexes.ascending("countryCode"),
                        new IndexOptions().name("idx_regions_countryCode")
                );

        controlPlane.collection(ControlPlane.Collections.CITIES, Document.class)
                .createIndex(
                        Indexes.ascending("regionCode"),
                        new IndexOptions().name("idx_cities_regionCode")
                );
        controlPlane.collection(ControlPlane.Collections.CITIES, Document.class)
                .createIndex(
                        Indexes.ascending("countryCode"),
                        new IndexOptions().name("idx_cities_countryCode")
                );

        // industries : _id = code (slug stable), pas d'index supplémentaire
        controlPlane.collection(ControlPlane.Collections.INDUSTRIES, Document.class)
                .createIndex(
                        Indexes.ascending("isActive"),
                        new IndexOptions().name("idx_industries_isActive")
                );

        // ─── refresh tokens ───
        // tokenHash = clé de lookup (SHA-256 du secret côté client). Unique.
        controlPlane.collection(ControlPlane.Collections.REFRESH_TOKENS, Document.class)
                .createIndex(
                        Indexes.ascending("tokenHash"),
                        new IndexOptions().unique(true).name("uniq_refresh_tokenHash")
                );
        // userId : permet "déconnecter toutes mes sessions" en une requête.
        controlPlane.collection(ControlPlane.Collections.REFRESH_TOKENS, Document.class)
                .createIndex(
                        Indexes.ascending("userId"),
                        new IndexOptions().name("idx_refresh_userId")
                );
        // familyId : sur reuse detection, on révoque toute la famille.
        controlPlane.collection(ControlPlane.Collections.REFRESH_TOKENS, Document.class)
                .createIndex(
                        Indexes.ascending("familyId"),
                        new IndexOptions().name("idx_refresh_familyId")
                );

        // ─── platform settings ───
        // _id = nom de la section (slug), unique implicite. Pas d'autre index
        // utile : la collection ne contient qu'une poignée d'entrées (email,
        // storage, payment.*, notifications) accédées par clé primaire.
        controlPlane.collection(ControlPlane.Collections.PLATFORM_SETTINGS, Document.class);

        log.info("Control plane schema bootstrap : OK");
    }
}
