package com.ntech.cabosse.site.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.UUID;

/**
 * Site du tenant (transformation ou point de vente).
 *
 * <p>Stockée dans la base du tenant ({@code tenant_<uuid>.sites}), pas
 * dans le control plane. Aucun {@code tenantId} sur le document : le
 * scoping est garanti par le choix de base via
 * {@code TenantMongoDatabaseProvider}.</p>
 *
 * <p>POJO simple (pas Panache) car Quarkus Mongo Panache fige le nom de
 * base au build-time via {@code @MongoEntity(database = ...)}, ce qui ne
 * convient pas aux entités tenant-scoped. Les accès passent par
 * {@link com.ntech.cabosse.site.repository.SiteRepository}.</p>
 */
public class SiteEntity {

    @BsonId
    public UUID id;

    /** {@link SiteType} sérialisé en String pour stabilité aux renames. */
    public String type;

    /** Libellé affiché à l'utilisateur (modifiable). */
    public String name;

    /**
     * Slug stable, utilisé comme FK depuis les autres documents
     * (stocks, achats…). Posé à la création et plus modifié ensuite.
     */
    public String code;

    // ─── Adresse (toute optionnelle au MVP) ───
    public String addressLine;
    public String cityId;       // référence catalog.cities (UUID stringifié)
    public String cityName;     // dénormalisé pour l'affichage rapide
    public String regionCode;
    public String countryCode;

    /** Coordonnées GPS WGS-84. Posées via map picker côté UI. */
    public Double latitude;
    public Double longitude;

    // ─── Contact ───
    public String phone;
    public String email;
    /** Nom du responsable opérationnel du site. */
    public String managerName;

    // ─── Détails libres ───
    /** Description libre — particularités d'accès, équipements, etc. */
    public String description;
    /** Horaires d'ouverture en texte libre au MVP ({@code "Lun-Ven 8h-17h"}). */
    public String openingHours;

    /** Désactivé → invisible des sélecteurs mais conservé en base. */
    public boolean active = true;

    public Instant createdAt;
    public Instant updatedAt;

    /** UUID du user qui a créé le site (audit). */
    public UUID createdBy;
}
