# Stockage de fichiers — Cabosse ERP

**Référence** : NEIBA-ARCH-2026-003 · Mai 2026
**Statut** : règle non négociable, à respecter dans tout le code Java backend.

Toute donnée binaire uploadée (logos, images produits, attachments BC, certificats, factures scannées, exports générés…) est gérée par un seul modèle : **`CloudFileEntity`**. Le binaire vit dans un backend de stockage configurable (filesystem local, S3-compatible, …) ; l'entité métier ne stocke que **l'UUID du fichier**, plus optionnellement quelques méta dupliquées pour l'affichage (mime, taille).

---

## 1. Pourquoi

Stocker du `byte[]` ou du `Binary` directement dans une entité métier produit trois symptômes garantis :

- **Lectures de liste qui tirent des Mo inutiles.** Un `GET /tenants` paginé qui retourne 20 documents avec chacun ~500 ko de logo embedded = 10 Mo de trafic à chaque appel.
- **Backup / dump qui explose.** `mongodump --db cabosse_control` archive aussi les bytes des logos, donc gonfle le coût de stockage et le temps de restauration.
- **Scaling vertical forcé.** La base devient le bottleneck I/O pour le service de fichiers, alors que celui-ci est par nature un object store.

Sortir les bytes des entités résout les trois en une seule décision architecturale.

---

## 2. Modèle `CloudFileEntity`

```java
@MongoEntity(database = ControlPlane.DATABASE, collection = ControlPlane.Collections.CLOUD_FILES)
public class CloudFileEntity extends PanacheMongoEntityBase {

    @BsonId
    public UUID id;

    /** Identifiant de backend : "local", "s3", "gridfs". */
    public String storageBackend;

    /** Chemin / clé relatif au backend (ex. "tenant/abc.../9d4e7.png"). */
    public String storagePath;

    public String mimeType;
    public long sizeBytes;

    /** Nom du fichier tel que choisi par l'uploader (pour Content-Disposition). */
    public String originalFileName;

    /**
     * Usage métier — détermine les règles de validation (taille max,
     * mimes autorisés). Conventions : "tenant.logo", "product.image",
     * "purchase_order.attachment", "user.avatar", "export.report"…
     */
    public String type;

    /** UUID de l'entité métier qui réfère ce fichier (ex. tenantId pour un logo). */
    public UUID ownerEntityId;

    /** Type de l'entité owner ("tenant", "product", "purchase_order"). */
    public String ownerEntityType;

    /** Pré-signed URL ou URL CDN publique. Null si servi par l'app via proxy. */
    public String publicUrl;
    public Instant publicUrlExpiresAt;

    public UUID uploadedBy;
    public Instant uploadedAt;
    public Instant lastAccessedAt;

    /** Soft delete avant nettoyage physique async. Null = actif. */
    public Instant archivedAt;
}
```

**Deux scopes :**
- `cabosse_control.cloud_files` — fichiers possédés par la plateforme (logos tenants, icônes plans…)
- `<tenant_db>.cloud_files` — fichiers possédés par un tenant (images produits, attachments BC, exports…)

Cohérent avec l'isolation database-per-tenant : supprimer un tenant supprime aussi les métadonnées de ses fichiers. Le binaire physique est nettoyé par un job orphan-scan (Phase D+).

---

## 3. Convention de chemin

Le `storagePath` suit toujours :

```
{ownerEntityType}/{ownerEntityId}/{fileId}.{ext}
```

Exemples :

```
local: ${file-storage.local.base-path}/tenant/a3f12c40.../9d4e7a..png
s3:    s3://${bucket}/tenant/a3f12c40.../9d4e7a..png
```

L'extension est dérivée du `mimeType` (`image/png` → `.png`, `image/svg+xml` → `.svg`, `application/pdf` → `.pdf`…). Une entrée `mimeType` inconnue tombe sur l'extension vide (le fichier reste valide mais sans hint visuel).

---

## 4. Abstraction `FileStorage`

```java
public interface FileStorage {
    /** "local" | "s3" | "gridfs" — alimente CloudFileEntity.storageBackend. */
    String backendId();

    /** Persiste le contenu. Retourne le path effectif (peut différer du suggéré). */
    String store(InputStream content, long sizeBytes, String relativePath);

    /** Ouvre un stream de lecture sur le fichier. À fermer par l'appelant. */
    InputStream open(String storagePath);

    /** Suppression physique. No-op si le fichier n'existe pas. */
    void delete(String storagePath);

    /**
     * URL pré-signée (S3) ou URL signée par jeton (local). Vide quand le
     * backend ne supporte pas l'exposition publique (forcer le proxy app).
     */
    Optional<URI> publicSignedUrl(String storagePath, Duration ttl);
}
```

### Implémentations

| Backend | Class | Notes |
|---|---|---|
| `local` | `LocalFileStorage` | Disque, sert via le proxy app (`GET /api/v1/admin/tenants/{id}/logo` ou équivalent). `publicSignedUrl` retourne `Optional.empty()` au MVP. |
| `s3` | `S3FileStorage` | AWS SDK / compatible MinIO. `publicSignedUrl` génère une URL pré-signée valide TTL. **Phase D ou plus tard.** |
| `gridfs` | `GridFsFileStorage` | Mongo natif. Pas prévu pour l'instant — option de repli si on veut tout dans Mongo. |

Le choix d'implémentation injecté est piloté par la config `application.file-storage.backend`. Au MVP, seul `local` est implémenté.

---

## 5. Configuration

`application.yml` (commun) ne porte rien — chaque environnement définit son backend.

```yaml
# application-dev.yml
application:
  file-storage:
    backend: local
    local:
      base-path: ./uploads-dev
      public-base-url: http://localhost:8080/api/v1/files
```

```yaml
# application-prod.yml
application:
  file-storage:
    backend: s3                              # quand S3 sera dispo
    s3:
      bucket: ${S3_BUCKET}
      region: ${S3_REGION:eu-west-3}
      endpoint: ${S3_ENDPOINT}               # null = AWS, set = MinIO/Wasabi
      access-key: ${S3_ACCESS_KEY}
      secret-key: ${S3_SECRET_KEY}
      public-cdn-domain: ${S3_PUBLIC_DOMAIN}
      presigned-url-ttl: PT1H
```

L'interface typée vit dans `com.ntech.cabosse.shared.storage.FileStorageConfig` (cf. règle CLAUDE.md §2.3 — pas de `@ConfigProperty` dans une classe métier).

---

## 6. Pattern d'usage côté service métier

Le service métier **ne touche jamais le `byte[]` directement** ni `CloudFileEntity` directement. Il passe par `FileUploadService` :

```java
@ApplicationScoped
public class TenantLogoService {

    @Inject FileUploadService uploads;
    @Inject TenantRepository tenants;
    @Inject TenantContext tenantContext;

    @Transactional
    public void attachLogo(UUID tenantId, byte[] bytes, String mimeType, String originalName) {
        TenantEntity tenant = tenants.findById(tenantId);
        if (tenant == null) throw new NotFoundException("Tenant introuvable");

        // 1. Si un logo existe déjà, on l'archive (soft delete).
        if (tenant.branding.logoFileId != null) {
            uploads.archive(tenant.branding.logoFileId);
        }

        // 2. Upload du nouveau (FileUploadService persiste CloudFileEntity + stocke le binaire)
        CloudFileEntity file = uploads.upload(
            bytes, mimeType, originalName,
            "tenant.logo",
            tenant.id, "tenant"
        );

        // 3. Mise à jour de l'entité métier — référence par UUID + cache des méta pour l'UI
        tenant.branding.logoFileId = file.id;
        tenant.branding.mimeType = file.mimeType;
        tenant.branding.sizeBytes = file.sizeBytes;
        tenant.updatedAt = Instant.now();
        tenant.updatedBy = tenantContext.userId();
        tenants.update(tenant);
    }
}
```

---

## 7. Cache des méta sur l'entité métier

L'entité métier (ex. `TenantBranding`) duplique `mimeType` et `sizeBytes` à côté du `logoFileId`. Justification :

- L'UI affiche souvent "logo personnalisé · 124 ko" dans la liste, ce qui sinon imposerait un `findById` sur `CloudFile` par tenant à chaque rendu.
- La duplication est réécrite par `FileUploadService` à chaque upload/archive, donc reste cohérente avec la source de vérité.
- Le `CloudFileEntity` reste la source unique pour : `originalFileName`, `storagePath`, `publicUrl`, dates d'audit, `ownerEntityType`/`ownerEntityId`.

**Règle** : seuls `mimeType` et `sizeBytes` sont dupliqués sur l'entité métier. Tout le reste vient de `CloudFile`.

---

## 8. URL publique et accès

**Au MVP (backend local) :**
- `CloudFileEntity.publicUrl` reste `null`.
- L'accès passe par un endpoint **proxy app** (`GET /api/v1/admin/tenants/{id}/logo` pour les logos tenants), qui résout `branding.logoFileId` → `CloudFile.storagePath` → `LocalFileStorage.open()` → sert le binaire avec son `mimeType` et l'en-tête `Authorization` exigé.
- TTL de cache HTTP : `private, max-age=300` (5 min).

**Quand S3 arrive (Phase D+) :**
- `S3FileStorage.publicSignedUrl` génère une URL pré-signée (TTL 1h).
- L'endpoint domain-specific (`GET /tenants/{id}/logo`) peut soit servir le binaire en proxy, soit renvoyer un `302 Found` vers l'URL pré-signée — au choix selon le couplage souhaité côté frontend.
- L'URL signée n'est jamais persistée sur l'entité (l'expiration courte rend tout cache obsolète) — `CloudFileEntity.publicUrl` ne sert que pour les ressources réellement publiques (jamais signées, ex. : assets marketing CDN).

---

## 9. Limites par type de fichier

Le service `FileUploadService.upload()` consulte un registre de règles par `type` :

| Type | Taille max | MIME autorisés |
|---|---|---|
| `tenant.logo` | 1 Mo | `image/png`, `image/jpeg`, `image/svg+xml`, `image/webp` |
| `product.image` | 2 Mo | `image/png`, `image/jpeg`, `image/webp` |
| `purchase_order.attachment` | 10 Mo | `application/pdf`, `image/png`, `image/jpeg` |
| `user.avatar` | 500 ko | `image/png`, `image/jpeg`, `image/webp` |
| `export.report` | 50 Mo | `application/pdf`, `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`, `text/csv` |

Tout `type` non listé est **rejeté** (422). Ajouter un nouveau type = ajouter une entrée à `FileUploadLimits` dans `shared.storage`.

---

## 10. Cycle de vie

| Évènement | Action |
|---|---|
| **Upload** | `FileUploadService.upload()` valide (type, taille, mime), génère un fileId, écrit dans le backend, persiste `CloudFileEntity`. |
| **Lecture** | `FileUploadService.open(fileId)` ouvre le stream depuis le backend, touche `lastAccessedAt`. |
| **Soft delete** | `FileUploadService.archive(fileId)` : `archivedAt = now()`. Le binaire reste sur le backend pour un éventuel rollback. |
| **Suppression physique** | Job orphan-scan (Phase D+) : pour les `CloudFile` `archivedAt < now() - 30j` ET non référencés par aucune entité métier, supprime du backend + supprime le doc. |
| **Remplacement** | Pattern systématique : archive l'ancien, upload le nouveau, update la ref sur l'entité métier. Jamais "écraser" un fichier en place. |

---

## 11. Migration de tenant (suppression RGPD)

Quand un tenant est supprimé (`dropDatabase()` côté plan donnée), les `CloudFile` du **plan contrôle** (logos tenants) doivent aussi être nettoyés. Le `TenantDeletionService` (Phase D) inclut une étape :

```java
cloudFiles.find("ownerEntityType = 'tenant' and ownerEntityId = ?1", tenantId)
    .stream()
    .forEach(f -> {
        storage.delete(f.storagePath);   // suppression physique immédiate
        cloudFiles.delete(f);
    });
```

Les `CloudFile` côté plan donnée disparaissent avec le `dropDatabase()` mais le binaire reste orphelin sur le storage backend — c'est le job orphan-scan qui le ramasse a posteriori (idempotent, OK avec une latence de quelques heures).

---

## 12. Récapitulatif des règles

| ❌ Interdit | ✅ Attendu |
|---|---|
| `byte[]` ou `Binary` dans un champ d'entité métier | Référence par `UUID` à `CloudFileEntity` |
| Service métier qui touche `FileStorage` directement | Passer par `FileUploadService` |
| Service métier qui touche `CloudFileEntity` directement | Passer par `FileUploadService` |
| Charge un type non listé dans la table §9 | Étendre `FileUploadLimits` avant d'utiliser |
| URL signée stockée sur l'entité métier | Generated on the fly à chaque accès |
| Suppression physique synchrone | Soft delete (`archivedAt`) + job orphan-scan |
| Écraser un fichier en place | Archive l'ancien + upload le nouveau + update la ref |

---

**Fin du document.**

*NEIBA Technologies · Cabosse ERP · Mai 2026 · v1.0*
