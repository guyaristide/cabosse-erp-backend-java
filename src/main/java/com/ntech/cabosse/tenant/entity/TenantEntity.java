package com.ntech.cabosse.tenant.entity;

import com.ntech.cabosse.shared.persistence.ControlPlane;
import com.ntech.cabosse.shared.tenant.TenantStatus;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Entrée canonique du registre des tenants. Vit dans le plan contrôle
 * ({@code cabosse_control.tenants}).
 *
 * <p>Porte tout le contexte métier d'un tenant : identité, branding,
 * légal, adresse, contact, facturation, préférences, activités,
 * certifications, statut technique + commercial. Le {@code databaseName}
 * référence la base tenant-scopée correspondante (plan donnée).</p>
 *
 * <p>Convention BSON : noms de champs en camelCase, identique au nom Java
 * (cf. CLAUDE.md §3.1 "camelCase partout"). Le {@code _id} est un UUID v7
 * sérialisé en string par {@link com.ntech.cabosse.shared.persistence.UuidStringCodec}.</p>
 */
@MongoEntity(database = ControlPlane.DATABASE, collection = ControlPlane.Collections.TENANTS)
public class TenantEntity extends PanacheMongoEntityBase {

    @BsonId
    public UUID id;

    /** Nom commercial. */
    public String name;

    /** Identifiant URL-safe unique ({@code coop-nommee-1}). */
    public String slug;

    /** Nom physique de la base MongoDB du tenant ({@code tenant_<uuid>}). */
    public String databaseName;

    // ─── Statuts ───
    public TenantStatus status;
    public CommercialStatus commercialStatus;
    /** Renseigné seulement si {@code commercialStatus == TRIAL}. */
    public Integer trialDurationDays;

    /**
     * Structure juridique du tenant. Orthogonale aux {@link #activities}.
     * Pilote l'activation des capacités {@code hasMembers} et
     * {@code hasSustainability} indépendamment de la filière.
     * Défaut {@link TenantOrganizationModel#PRIVATE_COMPANY} si non
     * précisé (cas des tenants historiques antérieurs à NEIBA-TECH-2026-003).
     */
    public TenantOrganizationModel organizationModel = TenantOrganizationModel.PRIVATE_COMPANY;

    /** Code du plan tarifaire (FK vers {@link com.ntech.cabosse.plan.entity.PlanEntity#code}). */
    public String planCode;

    /**
     * Abonnement actif (plan + période). Renseigné par l'action « activer
     * l'abonnement » du super-admin ; {@code null} tant qu'aucun abonnement
     * n'a été posé (ex. tenant en essai). Voir {@link TenantSubscription}.
     */
    public TenantSubscription subscription;

    // ─── Sub-documents ───
    public TenantBranding branding;
    public TenantLegal legal;
    public TenantAddress address;
    public TenantContact contact;
    public TenantBilling billing;
    public TenantPreferences preferences;

    /** 1..N activités, exactement une avec {@code isPrimary=true}. */
    public List<TenantActivity> activities = new ArrayList<>();

    /** Liste de codes ({@code "UTZ"}, {@code "Bio"}, etc.). */
    public List<String> certifications = new ArrayList<>();

    /** Nombre de sites à seeder au provisioning (modifiable après). */
    public int initialSitesCount;

    // ─── Audit ───
    public Instant createdAt;
    public Instant updatedAt;
    /** Renseigné à l'activation effective (passage {@code PROVISIONING → ACTIVE}). */
    public Instant activatedAt;
    /** Renseigné si {@code status == DELETED}. */
    public Instant deletedAt;
    /** Renseigné si {@code status == SUSPENDED}. */
    public Instant suspendedAt;

    /** UUID du super-admin qui a provisionné le tenant. */
    public UUID createdBy;
    /** UUID du dernier acteur ayant modifié l'entité. */
    public UUID updatedBy;

    /** Message d'erreur si {@code status == FAILED}. */
    public String provisioningError;

    public TenantEntity() {}
}
