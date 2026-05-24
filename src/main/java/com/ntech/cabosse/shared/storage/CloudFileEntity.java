package com.ntech.cabosse.shared.storage;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.UUID;

/**
 * Métadonnées d'un fichier uploadé. Source de vérité unique pour
 * tout binaire qui transite par Cabosse ERP (logos tenants, images
 * produits, attachements BC, exports générés, …).
 *
 * <p>Le binaire lui-même <strong>n'est jamais</strong> dans cette entité.
 * Il vit dans le backend pointé par {@link #storageBackend} +
 * {@link #storagePath}, accédé via {@link FileStorage}.</p>
 *
 * <p>POJO non Panache — la base cible dépend du {@link CloudFileScope},
 * résolu à l'exécution par {@link CloudFileRepository} (control plane
 * pour les fichiers plateforme, base du tenant pour les fichiers tenant
 * — cf. {@code docs/file-storage.md} §2).</p>
 *
 * <p>{@link #tenantId} est dénormalisé pour les fichiers de scope
 * {@link CloudFileScope#TENANT} (utile aux jobs de cleanup et au routing
 * lors d'un futur orphan-scan). {@code null} pour les fichiers
 * plateforme.</p>
 */
public class CloudFileEntity {

    @BsonId
    public UUID id;

    /** Backend où vit le binaire : "local", "s3", "gridfs". */
    public String storageBackend;

    /** Clé / chemin relatif au backend (ex. "article/abc.../9d4e7.png"). */
    public String storagePath;

    public String mimeType;
    public long sizeBytes;

    /** Nom du fichier tel que choisi par l'uploader. Pour Content-Disposition. */
    public String originalFileName;

    /**
     * Usage métier qui détermine la validation (taille max, mimes autorisés).
     * Conventions : "tenant.logo", "product.image", "purchase_order.attachment",
     * "user.avatar", "export.report", …
     */
    public String type;

    /** UUID de l'entité métier qui réfère ce fichier. */
    public UUID ownerEntityId;

    /** Type de l'entité owner ("tenant", "article", "purchase_order"). */
    public String ownerEntityType;

    /**
     * Tenant propriétaire — dénormalisé pour les fichiers de scope
     * {@link CloudFileScope#TENANT}. {@code null} pour scope
     * {@link CloudFileScope#PLATFORM}.
     */
    public UUID tenantId;

    /** Pré-signed URL ou URL CDN publique. Null si servi par proxy app. */
    public String publicUrl;
    public Instant publicUrlExpiresAt;

    public UUID uploadedBy;
    public Instant uploadedAt;
    public Instant lastAccessedAt;

    /** Soft delete avant nettoyage physique async. Null = actif. */
    public Instant archivedAt;

    public CloudFileEntity() {}
}
