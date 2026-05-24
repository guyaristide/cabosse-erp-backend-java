# Multi-tenant Database-per-tenant — Cabosse ERP

**Stack** : Quarkus 3.x · MongoDB 7.x (replica set) · Java 21
**Modèle** : Database-per-tenant sur cluster partagé
**Référence** : NEIBA-ARCH-2026-001 · Mai 2026

Ce document décrit l'implémentation **complète et opérationnelle** de l'isolation multi-tenant sur Cabosse ERP, depuis l'architecture jusqu'au provisioning d'un nouveau tenant. Il est conçu pour être suffisant à lui seul, sans nécessiter de lecture annexe.

---

## Table des matières

1. [Pourquoi Database-per-tenant](#1-pourquoi-database-per-tenant)
2. [Architecture en deux plans](#2-architecture-en-deux-plans)
3. [Configuration Quarkus](#3-configuration-quarkus)
4. [Modèle de données](#4-modèle-de-données)
5. [Authentification et JWT](#5-authentification-et-jwt)
6. [TenantContext et hydratation](#6-tenantcontext-et-hydratation)
7. [Providers d'accès aux données](#7-providers-daccès-aux-données)
8. [Pattern repository](#8-pattern-repository)
9. [Création d'un nouveau tenant — workflow complet](#9-création-dun-nouveau-tenant--workflow-complet)
10. [Migrations Mongock multi-tenant](#10-migrations-mongock-multi-tenant)
11. [Hors contexte HTTP — jobs et événements](#11-hors-contexte-http--jobs-et-événements)
12. [Back-office d'administration (M9)](#12-back-office-dadministration-m9)
13. [Backup, restauration, suppression](#13-backup-restauration-suppression)
14. [Tests](#14-tests)
15. [Règles de référence](#15-règles-de-référence)

---

## Note préalable — constantes partagées

Ce document référence des classes de constantes (`Roles`, `Events`, `JwtClaims`, `ControlPlane`, `CacheNames`) et un enum (`AuditEventType`) qui centralisent toutes les chaînes sémantiques partagées du projet. Leur définition complète est dans **`shared-constants.md`** (NEIBA-ARCH-2026-002).

Convention non négociable : **aucune string littérale** pour ces valeurs dans le code (`@RolesAllowed`, `@ConsumeEvent`, `@Claim`, `getCollection`, etc.). On passe **toujours** par la constante. Voir la règle 3.4 dans `CLAUDE.md`.

---

## 1. Pourquoi Database-per-tenant

Trois modèles d'isolation existent pour un SaaS multi-tenant sur MongoDB :

| Modèle | Isolation | Backup tenant | Suppression RGPD | Noisy neighbor |
|---|---|---|---|---|
| Discriminator (`tenant_id` partout) | Logique seulement | Non | Difficile | Fort |
| Collection-per-tenant | Moyenne | Partielle | Drop collections | Atténué |
| **Database-per-tenant** | **Physique** | `mongodump --db` | **`db.dropDatabase()`** | Isolé |

Pour Cabosse ERP, le choix est **Database-per-tenant sur cluster partagé**, justifié par :

- **Données financières et de traçabilité** (OHADA, fève → tablette) qui ne peuvent pas, jamais, fuiter.
- **Présence de filiales européennes** (Paris) qui rend RGPD applicable, avec droit à l'effacement.
- **Volumétrie attendue de 5 à 50 tenants** au démarrage : la complexité opérationnelle reste maîtrisable.
- **Élimination de toute classe de bug "filtre tenant oublié"** : la séparation est physique, pas conventionnelle.

Le coût acceptable : on perd l'active record Panache pour les entités tenant-scoped, qu'on remplace par un pattern Repository explicite sur le driver MongoDB. Le code est légèrement plus verbeux, l'isolation est incassable.

---

## 2. Architecture en deux plans

```
┌─────────────────────────────────────────────────────────────────┐
│                Quarkus app (Cabosse ERP)                        │
│                                                                 │
│  Controller → Service → Repository                              │
│                              │                                  │
│                              ▼                                  │
│         ┌────────────────────────────────────────┐              │
│         │ TenantMongoDatabaseProvider            │              │
│         │  (résout la DB courante au request)    │              │
│         └────────────┬───────────────────────────┘              │
│                      │                                          │
│         ┌────────────┴───────────────────────────┐              │
│         │ MongoClient (Quarkus, singleton)       │              │
│         └────────────┬───────────────────────────┘              │
└──────────────────────┼──────────────────────────────────────────┘
                       │
                       ▼ (un seul pool de connexions, partagé)
        ┌──────────────────────────────────────────┐
        │   MongoDB cluster (replica set)          │
        │                                          │
        │   ┌──────────────────┐                   │
        │   │  cabosse_control   │  ← plan contrôle  │
        │   │  • tenants       │                   │
        │   │  • users         │                   │
        │   │  • subscriptions │                   │
        │   │  • global_audit  │                   │
        │   │  • support_tickets │                 │
        │   └──────────────────┘                   │
        │                                          │
        │   ┌──────────────────┐                   │
        │   │  tenant_a3f12... │  ← plan donnée    │
        │   │  • purchases     │     (NOMMEE 1)    │
        │   │  • production    │                   │
        │   │  • sales         │                   │
        │   │  • stocks        │                   │
        │   │  • accounting    │                   │
        │   └──────────────────┘                   │
        │                                          │
        │   ┌──────────────────┐                   │
        │   │  tenant_8b1c4... │  ← plan donnée    │
        │   │  • ...           │     (autre coop)  │
        │   └──────────────────┘                   │
        └──────────────────────────────────────────┘
```

**Deux plans, deux responsabilités** :

- **Plan contrôle** (`cabosse_control`) : une seule base, opérée par l'équipe éditeur (NEIBA Technologies). Contient le registre des tenants, les utilisateurs, les abonnements, le journal d'audit global, les tickets de support. Sert M9 (back-office d'administration) et alimente la résolution `tenantId → databaseName` au login.
- **Plan donnée** : une base par tenant, totalement isolée des autres. Contient l'intégralité des données métier de ce tenant (M1 à M8).

**Un seul `MongoClient`** est utilisé pour tout l'écosystème. Le driver MongoDB gère un pool de connexions partagé au cluster, indépendamment du nombre de bases adressées. Adresser N bases ne consomme **pas** N pools.

---

## 3. Configuration Quarkus

### 3.1 Dépendances Gradle

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.quarkus:quarkus-mongodb-client")
    implementation("io.quarkus:quarkus-mongodb-panache")  // utilisé pour le control plane uniquement
    implementation("io.quarkus:quarkus-smallrye-jwt")
    implementation("io.quarkus:quarkus-smallrye-jwt-build")
    implementation("io.quarkus:quarkus-resteasy-reactive-jackson")
    implementation("io.quarkus:quarkus-hibernate-validator")
    implementation("io.quarkus:quarkus-cache")  // pour le cache de résolution tenant
    implementation("io.quarkus:quarkus-scheduler")

    // Mongock pour migrations multi-tenant
    implementation("io.mongock:mongock-standalone:5.4.4")
    implementation("io.mongock:mongodb-sync-v4-driver:5.4.4")
}
```

### 3.2 `application.yml`

```yaml
quarkus:
  mongodb:
    connection-string: ${MONGO_URI}
    # Aucune database par défaut configurée :
    # on adresse dynamiquement cabosse_control et tenant_*
    application-name: cabosse-erp
    write-concern:
      journal: true
    read-preference: primary

  smallrye-jwt:
    enabled: true

  cache:
    caffeine:
      tenant-registry:
        expire-after-write: 5M
        maximum-size: 1000

mp:
  jwt:
    verify:
      publickey:
        location: ${JWT_PUBLIC_KEY_LOCATION}
      issuer: ${JWT_ISSUER}

application:
  tenant-database-prefix: tenant_
```

### 3.3 Configuration typée

```java
@ConfigMapping(prefix = "application")
public interface ApplicationConfig {

    @WithName("tenant_database_prefix")
    String tenantDatabasePrefix();
}
```

### 3.4 Codec POJO

Le driver MongoDB sérialise les POJO via un `CodecRegistry`. Quarkus l'enregistre automatiquement avec le `PojoCodecProvider`. Aucune action n'est requise tant que les entités utilisent les annotations BSON standard (`@BsonProperty`, `@BsonId`, `@BsonIgnore`).

Pour les types non-supportés nativement (ex. : `UUID` stocké en string), un producer applicatif complète le registry :

```java
@ApplicationScoped
public class MongoCodecConfiguration {

    @Produces
    @ApplicationScoped
    public CodecRegistry mongoCodecRegistry() {
        return CodecRegistries.fromRegistries(
            MongoClientSettings.getDefaultCodecRegistry(),
            CodecRegistries.fromProviders(
                PojoCodecProvider.builder()
                    .automatic(true)
                    .build()
            ),
            CodecRegistries.fromCodecs(
                new UuidStringCodec()  // UUID ↔ String
            )
        );
    }
}
```

---

## 4. Modèle de données

### 4.1 Identifiants — UUID v7 partout

Toutes les entités utilisent un **UUID** comme identifiant unique, stocké directement en `_id` de MongoDB. Pas de séparation entre identifiant interne et identifiant exposé.

**Pourquoi pas `ObjectId` + `publicId` séparés ?**

Le pattern hérité du monde SQL (`BIGINT AUTO_INCREMENT` interne + `UUID` externe) n'a pas de raison d'exister en MongoDB :

- Pas de jointure native → aucun bénéfice à un identifiant numérique séquentiel.
- `_id` accepte n'importe quel type, pas besoin de le contraindre à ObjectId.
- Maintenir deux champs synchronisés sur chaque entité est une surface de bug et de migration. Un seul identifiant simplifie tout : références inter-entités, re-clé, fusion de tenants, export.

**Pourquoi UUID v7 plutôt que v4 ?**

UUID v4 est totalement aléatoire → mauvaise localité d'écriture sur l'index `_id` (B-tree splits fréquents sur les inserts). UUID v7 est **time-ordered** : 48 bits de timestamp ms en préfixe, 74 bits aléatoires en suffixe. Les inserts récents touchent des pages voisines de l'index, comportement proche d'un identifiant séquentiel sans la prédictibilité problématique (les 74 bits aléatoires garantissent l'opacité publique).

Java 21 ne génère pas nativement v7 (`UUID.randomUUID()` produit v4). On encapsule dans un service :

```kotlin
// build.gradle.kts
implementation("com.github.f4b6a3:uuid-creator:5.3.7")
```

```java
package com.ntech.cabosse.shared.persistence;

@ApplicationScoped
public class IdGenerator {

    /** Génère un UUID v7 (time-ordered). */
    public UUID newId() {
        return UuidCreator.getTimeOrderedEpoch();
    }
}
```

Tous les services et providers qui créent des entités injectent `IdGenerator` au lieu d'appeler `UUID.randomUUID()` directement. Avantage bonus : on peut mocker `IdGenerator` dans les tests pour des identifiants déterministes.

**Conséquences sur les références inter-entités**

Les références par identifiant utilisent directement le `_id` de l'entité cible. Pas de `supplier_public_id`, juste `supplier_id` qui pointe sur le `SupplierEntity._id`. Si demain on doit migrer / re-clé un fournisseur, **une seule** opération à propager, pas deux.

```java
public class PurchaseOrderEntity {
    @BsonId public UUID id;
    @BsonProperty("supplier_id") public UUID supplierId;   // → SupplierEntity.id
    @BsonProperty("site_id")     public UUID siteId;       // → SiteEntity.id
}
```

### 4.2 Plan contrôle — `cabosse_control`

**Collection `tenants`** — le registre canonique des tenants.

```java
@MongoEntity(database = ControlPlane.DATABASE, collection = ControlPlane.Collections.TENANTS)
public class TenantEntity extends PanacheMongoEntityBase {

    @BsonId
    public UUID id;

    @BsonProperty("name")
    public String name;

    @BsonProperty("slug")
    public String slug;   // ex: "nommee-1"

    @BsonProperty("database_name")
    public String databaseName;   // ex: "tenant_a3f12c40b8f44d7e9af1c83e6b2d1f9a"

    @BsonProperty("status")
    public TenantStatus status;   // PROVISIONING | ACTIVE | SUSPENDED | DELETED

    @BsonProperty("plan_code")
    public String planCode;       // "free", "pro", "enterprise"

    @BsonProperty("created_at")
    public Instant createdAt;

    @BsonProperty("activated_at")
    public Instant activatedAt;
}
```

**Collection `users`** — utilisateurs rattachés à un tenant.

```java
@MongoEntity(database = ControlPlane.DATABASE, collection = ControlPlane.Collections.USERS)
public class UserEntity extends PanacheMongoEntityBase {

    @BsonId
    public UUID id;

    @BsonProperty("email")
    public String email;

    @BsonProperty("password_hash")
    public String passwordHash;

    @BsonProperty("tenant_id")
    public UUID tenantId;   // → TenantEntity.id

    @BsonProperty("roles")
    public Set<String> roles;

    @BsonProperty("status")
    public UserStatus status;     // ACTIVE | INVITED | DISABLED

    @BsonProperty("created_at")
    public Instant createdAt;
    @BsonProperty("updated_at")
    public Instant updatedAt;
}
```

**Collection `global_audit`** — journal d'audit global, en particulier des accès cross-tenant par les super-admins plateforme.

```java
@MongoEntity(database = ControlPlane.DATABASE, collection = ControlPlane.Collections.GLOBAL_AUDIT)
public class GlobalAuditEntry extends PanacheMongoEntityBase {

    @BsonId public UUID id;
    @BsonProperty("event_type") public AuditEventType eventType;
    @BsonProperty("actor_id") public UUID actorId;
    @BsonProperty("tenant_id") public UUID tenantId;
    @BsonProperty("reason") public String reason;
    @BsonProperty("payload") public Document payload;
    @BsonProperty("occurred_at") public Instant occurredAt;

    /** Factory de construction — préférée à l'assignation manuelle des champs. */
    public static GlobalAuditEntry of(AuditEventType type, UUID actorId,
                                       UUID tenantId, String reason) {
        GlobalAuditEntry entry = new GlobalAuditEntry();
        entry.eventType = type;
        entry.actorId = actorId;
        entry.tenantId = tenantId;
        entry.reason = reason;
        entry.payload = new Document();
        entry.occurredAt = Instant.now();
        return entry;
    }
}
```

### 4.3 Plan donnée — `tenant_<uuid>`

Chaque base tenant contient les collections métier. Aucun champ `tenant_id` n'est nécessaire : la base **est** le scope tenant.

```java
public class PurchaseOrderEntity {

    @BsonId
    public UUID id;

    @BsonProperty("reference")
    public String reference;

    @BsonProperty("supplier_id")
    public UUID supplierId;

    @BsonProperty("site_id")
    public UUID siteId;

    @BsonProperty("status")
    public PurchaseOrderStatus status;

    @BsonProperty("lines")
    public List<PurchaseOrderLine> lines;

    @BsonProperty("total_xof")
    public BigDecimal totalXof;

    @BsonProperty("created_at")
    public Instant createdAt;
    @BsonProperty("updated_at")
    public Instant updatedAt;
    @BsonProperty("created_by")
    public UUID createdBy;
    @BsonProperty("updated_by")
    public UUID updatedBy;
}
```

**Note importante** : les entités tenant-scoped **n'étendent pas** `PanacheMongoEntity` et **ne portent pas** `@MongoEntity`. L'active record statique de Panache résoudrait une base fixe au compile-time, ce qu'on refuse. Ces entités sont de simples POJO sérialisables.

### 4.4 Convention de nommage des bases

Le nom de la base tenant suit le pattern :

```
<prefix><uuid_sans_tirets>
exemple : tenant_a3f12c40b8f44d7e9af1c83e6b2d1f9a
```

- Préfixe configurable via `application.tenant_database_prefix`.
- UUID v4 sans tirets pour rester compatible avec les contraintes MongoDB (pas de tirets dans certaines opérations admin) et avoir un nom de longueur prédictible.

---

## 5. Authentification et JWT

### 5.1 Flux de login

```
1. Client POST /api/v1/auth/login { email, password }
2. AuthService :
   a. Cherche l'utilisateur dans cabosse_control.users par email
   b. Vérifie le hash de mot de passe
   c. Charge le tenant via UserEntity.tenantId
   d. Vérifie que le tenant est ACTIVE
   e. Émet un JWT signé contenant :
      - sub                = user.id
      - upn                = user.email
      - groups             = user.roles
      - tenantId           = tenant.id
      - tenantDatabaseName = tenant.databaseName   ← clé du routage
      - iat, exp, iss
3. Retourne { access_token, refresh_token } au client
```

**Décision de design** : `tenantDatabaseName` est embarqué dans le JWT pour éviter une lecture du control plane à chaque requête. Le token a une durée courte (15 minutes typique) ; en cas de rename de tenant, les sessions actives expirent et le client refait un login.

### 5.2 Émission du JWT

```java
@ApplicationScoped
public class JwtIssuer {

    @ConfigProperty(name = "mp.jwt.verify.issuer")
    String issuer;

    public String issueAccessToken(UserEntity user, TenantEntity tenant) {
        return Jwt.issuer(issuer)
            .subject(user.id.toString())
            .upn(user.email)
            .groups(user.roles)
            .claim(JwtClaims.TENANT_ID, tenant.id.toString())
            .claim(JwtClaims.TENANT_DATABASE_NAME, tenant.databaseName)
            .expiresIn(Duration.ofMinutes(15))
            .sign();
    }
}
```

### 5.3 Validation

La validation est gérée par `quarkus-smallrye-jwt`. La clé publique est configurée via `mp.jwt.verify.publickey.location`. L'issuer attendu est vérifié systématiquement.

---

## 6. TenantContext et hydratation

### 6.1 Le bean `TenantContext`

```java
@RequestScoped
public class TenantContext {

    private UUID tenantId;
    private String databaseName;
    private UUID userId;
    private Set<String> roles;

    public UUID tenantId() {
        return require(tenantId, JwtClaims.TENANT_ID);
    }

    public String databaseName() {
        return require(databaseName, "databaseName");
    }

    public UUID userId() {
        return require(userId, "userId");
    }

    public Set<String> roles() {
        return roles != null ? roles : Set.of();
    }

    public boolean isInitialized() {
        return tenantId != null && databaseName != null;
    }

    void initialize(UUID tenantId, String databaseName, UUID userId, Set<String> roles) {
        this.tenantId = tenantId;
        this.databaseName = databaseName;
        this.userId = userId;
        this.roles = roles;
    }

    private <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalStateException(
                "TenantContext." + name + " non initialisé. "
              + "Cet endpoint est-il protégé par @Authenticated ?");
        }
        return value;
    }
}
```

### 6.2 Filtre JAX-RS qui hydrate le contexte

```java
@Provider
@Authenticated
@Priority(Priorities.AUTHENTICATION + 100)
public class TenantContextFilter implements ContainerRequestFilter {

    @Inject TenantContext tenantContext;

    @Inject @Claim(JwtClaims.TENANT_ID)           ClaimValue<String> tenantIdClaim;
    @Inject @Claim(JwtClaims.TENANT_DATABASE_NAME) ClaimValue<String> databaseNameClaim;
    @Inject @Claim(standard = Claims.sub) ClaimValue<String> subClaim;
    @Inject @Claim(standard = Claims.groups) ClaimValue<Set<String>> rolesClaim;

    @Override
    public void filter(ContainerRequestContext ctx) {
        tenantContext.initialize(
            UUID.fromString(tenantIdClaim.getValue()),
            databaseNameClaim.getValue(),
            UUID.fromString(subClaim.getValue()),
            rolesClaim.getValue()
        );
    }
}
```

À partir de l'exécution de ce filtre, **toute injection de `TenantContext` dans n'importe quelle classe CDI** retourne un contexte hydraté pour la requête courante.

### 6.3 Validation supplémentaire du tenant (optionnel mais recommandé)

Pour bloquer les tokens valides dont le tenant a été suspendu entre l'émission et l'usage, le filtre peut consulter un cache du registre tenant :

```java
@Provider
@Authenticated
@Priority(Priorities.AUTHENTICATION + 110)
public class TenantStatusGuard implements ContainerRequestFilter {

    @Inject TenantRegistryCache registry;
    @Inject TenantContext tenantContext;

    @Override
    public void filter(ContainerRequestContext ctx) {
        TenantStatus status = registry.statusOf(tenantContext.tenantId());
        if (status != TenantStatus.ACTIVE) {
            ctx.abortWith(
                Response.status(Response.Status.FORBIDDEN)
                    .entity(new ApiResponse<>(403, "tenant " + status, null))
                    .build()
            );
        }
    }
}

@ApplicationScoped
public class TenantRegistryCache {

    @Inject MongoClient mongoClient;
    @Inject ApplicationConfig config;

    @CacheResult(cacheName = CacheNames.TENANT_REGISTRY)
    public TenantStatus statusOf(UUID tenantId) {
        Document doc = mongoClient.getDatabase(ControlPlane.DATABASE)
            .getCollection("tenants")
            .find(Filters.eq("_id", tenantId))
            .projection(Projections.include("status"))
            .first();

        if (doc == null) return TenantStatus.DELETED;
        return TenantStatus.valueOf(doc.getString("status"));
    }
}
```

TTL configuré dans `application.yml` (5 minutes par défaut). Une suspension de tenant est donc effective au plus tard 5 minutes après changement de statut.

---

## 7. Providers d'accès aux données

Deux providers, deux scopes. **Aucun service métier ne doit injecter `MongoClient` directement** — toujours passer par l'un des deux providers ci-dessous.

### 7.1 `ControlPlaneProvider` — accès au plan contrôle

```java
@ApplicationScoped
public class ControlPlaneProvider {

    @Inject MongoClient mongoClient;
    @Inject ApplicationConfig config;

    public MongoDatabase database() {
        return mongoClient.getDatabase(ControlPlane.DATABASE);
    }

    public <T> MongoCollection<T> collection(String name, Class<T> type) {
        return database().getCollection(name, type);
    }
}
```

Usage typique : services d'authentification, gestion des tenants par M9, journal d'audit global.

### 7.2 `TenantMongoDatabaseProvider` — accès au plan donnée du tenant courant

```java
@ApplicationScoped
public class TenantMongoDatabaseProvider {

    @Inject MongoClient mongoClient;
    @Inject TenantContext tenantContext;

    public MongoDatabase database() {
        return mongoClient.getDatabase(tenantContext.databaseName());
    }

    public <T> MongoCollection<T> collection(String name, Class<T> type) {
        return database().getCollection(name, type);
    }
}
```

**Point clé** : ce provider lit `tenantContext.databaseName()` à chaque appel. Si le `TenantContext` n'est pas initialisé (par exemple : endpoint mal configuré, hors contexte HTTP sans `@ActivateRequestContext`), l'appel échoue avec un `IllegalStateException` explicite — pas de fuite silencieuse possible.

---

## 8. Pattern repository

### 8.1 Repositories du control plane

Le control plane utilise une base fixe. **Panache active record est autorisé ici**, parce que le `@MongoEntity(database = ControlPlane.DATABASE)` résout correctement au compile-time.

```java
@ApplicationScoped
public class UserRepository implements PanacheMongoRepository<UserEntity> {

    public Optional<UserEntity> findByEmail(String email) {
        return find("email", email).firstResultOptional();
    }

    public Optional<UserEntity> findById(UUID id) {
        return find("_id", id).firstResultOptional();
    }
}
```

### 8.2 Repositories du plan donnée tenant

Pour les entités tenant-scoped, **on n'utilise pas Panache active record**. On passe par le `TenantMongoDatabaseProvider`.

```java
@ApplicationScoped
public class PurchaseOrderRepository {

    private static final String COLLECTION = "purchase_orders";

    @Inject TenantMongoDatabaseProvider db;
    @Inject IdGenerator idGenerator;

    private MongoCollection<PurchaseOrderEntity> coll() {
        return db.collection(COLLECTION, PurchaseOrderEntity.class);
    }

    // === Lectures ===

    public Optional<PurchaseOrderEntity> findById(UUID id) {
        return Optional.ofNullable(
            coll().find(Filters.eq("_id", id)).first()
        );
    }

    public List<PurchaseOrderEntity> findBySupplier(UUID supplierId,
                                                     int skip, int limit) {
        return coll()
            .find(Filters.eq("supplier_id", supplierId))
            .sort(Sorts.descending("created_at"))
            .skip(skip)
            .limit(limit)
            .into(new ArrayList<>());
    }

    public long countBySupplier(UUID supplierId) {
        return coll().countDocuments(Filters.eq("supplier_id", supplierId));
    }

    // === Écritures ===

    public void persist(PurchaseOrderEntity entity) {
        Instant now = Instant.now();
        if (entity.id == null) entity.id = idGenerator.newId();
        if (entity.createdAt == null) entity.createdAt = now;
        entity.updatedAt = now;
        coll().insertOne(entity);
    }

    public void update(PurchaseOrderEntity entity) {
        entity.updatedAt = Instant.now();
        coll().replaceOne(Filters.eq("_id", entity.id), entity);
    }

    public void deleteByPublicId(UUID id) {
        coll().deleteOne(Filters.eq("_id", id));
    }
}
```

### 8.3 Service qui consomme le repository

```java
@ApplicationScoped
public class PurchaseOrderService {

    @Inject PurchaseOrderRepository repository;
    @Inject TenantContext tenantContext;

    public PurchaseOrderResponseDto create(PurchaseOrderRequestDto request) {
        PurchaseOrderEntity entity = PurchaseOrderMapper.toEntity(request);
        entity.createdBy = tenantContext.userId();
        entity.updatedBy = tenantContext.userId();
        repository.persist(entity);
        return PurchaseOrderMapper.toDto(entity);
    }

    public Pagination<PurchaseOrderResponseDto> listBySupplier(
            UUID supplierId, int page, int perPage) {

        int skip = page * perPage;
        List<PurchaseOrderEntity> items =
            repository.findBySupplier(supplierId, skip, perPage);
        long total = repository.countBySupplier(supplierId);

        return new Pagination<>(
            total,
            (int) Math.ceil((double) total / perPage),
            (long) perPage,
            new String[] { "created_at" },
            "desc",
            page,
            page + 1,
            Math.max(0, page - 1),
            Map.of("supplier_id", supplierId.toString()),
            items.stream().map(PurchaseOrderMapper::toDto).toList()
        );
    }
}
```

Aucune référence au tenant, nulle part, dans le service ou le repository. La base **est** le scope.

---

## 9. Création d'un nouveau tenant — workflow complet

C'est le scénario opéré par **M9 (back-office d'administration)**. Il s'agit d'une commande déclenchée par Hervé (super-admin plateforme, équipe NEIBA Technologies) après signature du contrat avec une nouvelle coopérative. Cible : **moins de 5 minutes** entre le clic et l'envoi de l'e-mail d'invitation à l'admin du tenant.

### 9.1 Vue d'ensemble du workflow

```
[M9 admin clique "Créer tenant"]
        │
        ▼
1. Validation du DTO et unicité du slug
        │
        ▼
2. Insertion du document tenant en statut PROVISIONING (control plane)
        │
        ▼
3. Création physique de la base tenant_<uuid>
        │
        ▼
4. Exécution des migrations Mongock sur cette base
        │
        ▼
5. Création des index nécessaires
        │
        ▼
6. Seed des référentiels initiaux (plan SYSCOHADA, unités, devises…)
        │
        ▼
7. Création du premier utilisateur admin du tenant (statut INVITED)
        │
        ▼
8. Passage du tenant en statut ACTIVE
        │
        ▼
9. Émission d'un événement TenantProvisioned sur l'event bus
        │
        ▼
10. Consommateur : envoi de l'e-mail d'invitation à l'admin
```

### 9.2 DTO de commande

**Décision Phase B (Option 1) : DTO étendu**. Le formulaire frontend captant ~25 champs en une seule étape, le backend les absorbe d'un coup pour ne pas laisser le tenant en état partiel après provisioning. Voir [`delivery-roadmap.md`](./delivery-roadmap.md) §"Sujets à arbitrer".

```java
public record CreateTenantPayloadDto(
    // Identité
    @NotBlank @Size(min = 3, max = 120) String name,
    @NotBlank @Pattern(regexp = "^[a-z0-9-]{3,50}$") String slug,
    @Pattern(regexp = "^#[0-9a-fA-F]{6}$") String brandColor,

    // Sub-payloads
    @Valid @NotNull LegalPayload legal,            // legalName, legalForm, rccm, taxId, vatNumber
    @Valid @NotNull AddressPayload address,        // street, postalCode, city, country (ISO 3166-1 alpha-2)
    @Valid @NotNull ContactPayload contact,        // name, email, phone
    @Valid @NotNull BillingPayload billing,        // email, cycle (MONTHLY|YEARLY)
    @Valid @NotNull PreferencesPayload preferences,// currency, language, timezone

    @NotEmpty List<@Valid ActivityPayload> activities,   // 1..N, exactement une isPrimary
    List<String> certifications,

    @Min(1) @Max(50) int initialSitesCount,
    @NotBlank String planCode,
    @NotNull CommercialStatus commercialStatus,    // TRIAL | PILOT | PRODUCTION
    @Min(1) @Max(90) Integer trialDurationDays,    // requis ssi commercialStatus == TRIAL

    @Valid @NotNull AdminPayload admin             // email, firstName, lastName
) {}
```

**Endpoint** : `POST /api/v1/admin/tenants` en `multipart/form-data` avec :
- partie `payload` (`application/json`) : le DTO ci-dessus
- partie `logo` (optionnelle, fichier) : PNG / JPEG / SVG / WebP, 1 Mo max

Si fourni, le logo est persisté dans une collection séparée `cabosse_control.tenant_logos` (1 doc par tenant, `_id == tenantId`) — pas inline dans `TenantEntity` pour éviter de gonfler les lectures de liste.

### 9.2 bis Endpoints logo dédiés

Une fois le tenant créé, le logo se gère via trois endpoints séparés (le `PUT /tenants/{id}` JSON ne le touche pas) :

| Méthode | Chemin | Body | Description |
|---|---|---|---|
| `GET` | `/api/v1/admin/tenants/{id}/logo` | — | Renvoie le binaire avec son `Content-Type`. Utilisable directement comme `src` d'un `<img>`. Cacheable 5 min. |
| `PUT` | `/api/v1/admin/tenants/{id}/logo` | `multipart/form-data` (partie `logo`) | Remplace le logo existant (ou crée). |
| `DELETE` | `/api/v1/admin/tenants/{id}/logo` | — | Retire le logo. No-op si absent. |

Le `TenantBranding` du `TenantEntity` ne stocke que les méta (`brandColor`, `logoMimeType`, `logoSizeBytes`) — utilisées pour afficher "logo personnalisé · 124 ko" dans la liste sans tirer les bytes.

### 9.3 Le service de provisioning

```java
@ApplicationScoped
public class TenantProvisioningService {

    @Inject MongoClient mongoClient;
    @Inject ControlPlaneProvider controlPlane;
    @Inject ApplicationConfig config;
    @Inject TenantMigrationRunner migrationRunner;
    @Inject TenantSeedingService seedingService;
    @Inject UserRepository userRepository;
    @Inject EventBus eventBus;
    @Inject AuditLogger audit;
    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    @Transactional
    @RolesAllowed(Roles.PLATFORM_ADMIN)
    public TenantId provision(CreateTenantRequestDto request) {

        // 1. Validation d'unicité
        ensureSlugIsUnique(request.slug());
        ensureAdminEmailIsUnique(request.adminEmail());

        // 2. Génération de l'identité du tenant
        UUID tenantId = idGenerator.newId();
        String databaseName = config.tenantDatabasePrefix()
            + tenantId.toString().replace("-", "");

        // 3. Enregistrement initial dans le control plane
        TenantEntity tenant = registerTenant(request, tenantId, databaseName);

        try {
            // 4. Création de la base physique
            createPhysicalDatabase(databaseName);

            // 5. Exécution des migrations
            migrationRunner.runMigrationsFor(databaseName);

            // 6. Seed des référentiels
            seedingService.seedReferentials(databaseName);

            // 7. Création de l'admin du tenant
            UserEntity admin = createTenantAdmin(request, tenantId);

            // 8. Activation
            activateTenant(tenant.id);

            // 9. Audit
            audit.logTenantProvisioned(tenantId, request.adminEmail());

            // 10. Événement d'activation (envoi d'invitation asynchrone)
            eventBus.publish(Events.TENANT_PROVISIONED,
                new TenantProvisionedEvent(tenantId, admin.id,
                                            admin.email, request.adminFirstName));

            return new TenantId(tenantId);

        } catch (Exception e) {
            // Rollback : marquer en échec, le cleanup est fait par un job dédié
            markProvisioningFailed(tenant.id, e.getMessage());
            throw new BusinessException("Tenant provisioning failed", e);
        }
    }

    private TenantEntity registerTenant(CreateTenantRequestDto request,
                                        UUID id, String databaseName) {
        TenantEntity tenant = new TenantEntity();
        tenant.id = id;
        tenant.name = request.name();
        tenant.slug = request.slug();
        tenant.databaseName = databaseName;
        tenant.status = TenantStatus.PROVISIONING;
        tenant.planCode = request.planCode();
        tenant.createdAt = Instant.now();
        tenant.persist();
        return tenant;
    }

    private void createPhysicalDatabase(String databaseName) {
        // MongoDB crée une base lors de la première écriture.
        // On force la création via une collection technique.
        mongoClient.getDatabase(databaseName)
            .createCollection("_provisioning_marker");
    }

    private UserEntity createTenantAdmin(CreateTenantRequestDto request,
                                          UUID tenantId) {
        String temporaryPassword = SecureTokenGenerator.generate(32);
        UserEntity admin = new UserEntity();
        admin.id = idGenerator.newId();
        admin.email = request.adminEmail();
        admin.passwordHash = passwordHasher.hash(temporaryPassword);
        admin.tenantId = tenantId;
        admin.roles = Set.of(Roles.TENANT_ADMIN);
        admin.status = UserStatus.INVITED;
        admin.createdAt = Instant.now();
        admin.updatedAt = admin.createdAt;
        userRepository.persist(admin);
        return admin;
    }

    private void activateTenant(UUID id) {
        controlPlane.collection(ControlPlane.Collections.TENANTS, TenantEntity.class).updateOne(
            Filters.eq("_id", id),
            Updates.combine(
                Updates.set("status", TenantStatus.ACTIVE.name()),
                Updates.set("activated_at", Instant.now())
            )
        );
    }

    private void markProvisioningFailed(UUID id, String error) {
        controlPlane.collection(ControlPlane.Collections.TENANTS, TenantEntity.class).updateOne(
            Filters.eq("_id", id),
            Updates.combine(
                Updates.set("status", TenantStatus.FAILED.name()),
                Updates.set("provisioning_error", error)
            )
        );
    }

    // ... ensureSlugIsUnique, ensureAdminEmailIsUnique
}
```

### 9.4 Consommateur de l'événement — envoi de l'invitation

```java
@ApplicationScoped
public class TenantInvitationListener {

    @Inject MailService mailService;
    @Inject InvitationTokenService tokenService;

    @ConsumeEvent(Events.TENANT_PROVISIONED)
    public void onTenantProvisioned(TenantProvisionedEvent event) {
        String invitationToken = tokenService.issueInvitation(
            event.adminUserId(),
            Duration.ofDays(7)
        );

        mailService.sendInvitation(
            event.adminEmail(),
            event.adminFirstName(),
            invitationToken
        );
    }
}
```

L'envoi de mail étant traité hors de la transaction principale, un échec d'envoi ne rollbacke pas le provisioning. Le mail est re-tentable indépendamment.

### 9.5 États possibles du tenant

| Statut | Description | Connexion possible |
|---|---|---|
| `PROVISIONING` | Création en cours | Non |
| `ACTIVE` | Opérationnel | Oui |
| `SUSPENDED` | Suspendu (impayé, demande client) | Non |
| `FAILED` | Échec de provisioning, cleanup pending | Non |
| `DELETED` | Supprimé (data archivée puis dropped) | Non |

Seul le statut `ACTIVE` autorise une connexion utilisateur. Le `TenantStatusGuard` (section 6.3) bloque tout le reste avec un `403`.

---

## 10. Migrations Mongock multi-tenant

### 10.1 Structure d'une migration

```java
@ChangeUnit(id = "create_purchase_orders_indexes", order = "001", author = "neiba")
public class M001_CreatePurchaseOrdersIndexes {

    @Execution
    public void execute(MongoDatabase database) {
        // L'index sur _id est créé automatiquement par MongoDB, on ne l'ajoute pas ici.
        database.getCollection("purchase_orders").createIndexes(List.of(
            new IndexModel(Indexes.ascending("supplier_id"),
                new IndexOptions().name("idx_supplier")),
            new IndexModel(Indexes.descending("created_at"),
                new IndexOptions().name("idx_created_at_desc"))
        ));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("purchase_orders").dropIndexes();
    }
}
```

Les migrations sont placées dans `com.ntech.cabosse.migrations`, **structurées par ordre numérique strict** (`M001_...`, `M002_...`, etc.).

### 10.2 Runner multi-tenant

```java
@ApplicationScoped
public class TenantMigrationRunner {

    @Inject MongoClient mongoClient;

    private static final String MIGRATION_PACKAGE =
        "com.ntech.cabosse.migrations";

    public void runMigrationsFor(String databaseName) {
        ConnectionDriver driver = MongoSync4Driver.withDefaultLock(
            mongoClient, databaseName);

        MongockStandalone.builder()
            .setDriver(driver)
            .addMigrationScanPackage(MIGRATION_PACKAGE)
            .setTrackIgnored(true)
            .buildRunner()
            .execute();
    }
}
```

Chaque base tenant porte sa propre collection `mongockChangeLog` qui trace les migrations appliquées. Une migration ratée sur un tenant n'impacte pas les autres.

### 10.3 Migration de tous les tenants au démarrage

À chaque déploiement de l'application, on applique les nouvelles migrations à tous les tenants ACTIVE :

```java
@ApplicationScoped
public class StartupMigrationRunner {

    @Inject TenantMigrationRunner runner;
    @Inject ControlPlaneProvider controlPlane;
    @Inject Logger log;

    void onStart(@Observes StartupEvent ev) {
        controlPlane.collection(ControlPlane.Collections.TENANTS, TenantEntity.class)
            .find(Filters.eq("status", TenantStatus.ACTIVE.name()))
            .forEach(tenant -> {
                try {
                    runner.runMigrationsFor(tenant.databaseName);
                    log.infof("Migrations applied for tenant %s", tenant.slug);
                } catch (Exception e) {
                    log.errorf(e, "Migration failed for tenant %s", tenant.slug);
                    // alerte, mais on ne bloque pas le démarrage : les autres tenants
                    // doivent rester opérationnels
                }
            });
    }
}
```

### 10.4 Application ciblée à un tenant unique

Pour les cas où l'équipe d'administration plateforme doit appliquer manuellement une migration à un tenant donné (debug, reprise après échec), un endpoint M9 expose la commande :

```java
@Path("/api/v1/admin/tenants/{tenantId}/migrations")
@RolesAllowed(Roles.PLATFORM_ADMIN)
public class TenantMigrationResource {

    @Inject TenantMigrationRunner runner;
    @Inject ControlPlaneProvider controlPlane;

    @POST
    public Response runMigrations(@PathParam("tenantId") UUID tenantId) {
        TenantEntity tenant = controlPlane
            .collection("tenants", TenantEntity.class)
            .find(Filters.eq("_id", tenantId))
            .first();

        if (tenant == null) throw new NotFoundException("tenant");

        runner.runMigrationsFor(tenant.databaseName);
        return Response.ok(new ApiResponse<>(200, "OK", null)).build();
    }
}
```

---

## 11. Hors contexte HTTP — jobs et événements

### 11.1 Le problème

`TenantContext` est `@RequestScoped`. Hors d'une requête HTTP (job `@Scheduled`, consommateur `@ConsumeEvent` sans propagation HTTP), il n'existe pas. Les repositories tenant-scoped lèvent alors `IllegalStateException`.

### 11.2 La solution : activation manuelle du contexte

```java
@ApplicationScoped
public class TenantAwareExecutor {

    @Inject TenantContext tenantContext;

    @ActivateRequestContext
    public void runForTenant(UUID tenantId, String databaseName,
                              Runnable task) {
        tenantContext.initialize(tenantId, databaseName,
                                  SYSTEM_USER_PUBLIC_ID, Set.of(Roles.SYSTEM));
        try {
            task.run();
        } finally {
            // Le request context se ferme à la fin du @ActivateRequestContext
        }
    }

    private static final UUID SYSTEM_USER_PUBLIC_ID =
        UUID.fromString("00000000-0000-0000-0000-000000000000");
}
```

### 11.3 Exemple : job de nettoyage des devis expirés

```java
@ApplicationScoped
public class QuoteCleanupJob {

    @Inject ControlPlaneProvider controlPlane;
    @Inject TenantAwareExecutor executor;
    @Inject QuoteService quoteService;

    @Scheduled(cron = "0 0 2 * * ?")  // tous les jours à 2h
    void cleanupExpiredQuotes() {
        controlPlane.collection(ControlPlane.Collections.TENANTS, TenantEntity.class)
            .find(Filters.eq("status", TenantStatus.ACTIVE.name()))
            .forEach(tenant ->
                executor.runForTenant(tenant.id, tenant.databaseName, () ->
                    quoteService.deleteExpiredOlderThan(Duration.ofDays(90))
                )
            );
    }
}
```

### 11.4 Consommateur event bus tenant-aware

Quand un événement est publié **depuis** un contexte tenant, on embarque le tenant dans le payload de l'événement, et le consommateur le réactive :

```java
public record SendPurchaseOrderConfirmationEvent(
    UUID tenantId,
    String tenantDatabaseName,
    UUID purchaseOrderPublicId,
    String recipientEmail
) {}

@ApplicationScoped
public class PurchaseOrderEmailListener {

    @Inject TenantAwareExecutor executor;
    @Inject PurchaseOrderMailService mailService;

    @ConsumeEvent(Events.PURCHASE_ORDER_CONFIRMED)
    public void onPurchaseOrderConfirmed(SendPurchaseOrderConfirmationEvent ev) {
        executor.runForTenant(ev.tenantId(), ev.tenantDatabaseName(),
            () -> mailService.sendConfirmation(
                ev.purchaseOrderPublicId(), ev.recipientEmail())
        );
    }
}
```

---

## 12. Back-office d'administration (M9)

### 12.1 Annuaire des tenants

```java
@ApplicationScoped
public class TenantRegistryService {

    @Inject ControlPlaneProvider controlPlane;

    @RolesAllowed(Roles.PLATFORM_ADMIN)
    public Pagination<TenantSummaryDto> list(int page, int perPage,
                                              TenantStatus statusFilter) {
        Bson filter = statusFilter != null
            ? Filters.eq("status", statusFilter.name())
            : new Document();

        List<TenantEntity> items = controlPlane
            .collection("tenants", TenantEntity.class)
            .find(filter)
            .sort(Sorts.descending("created_at"))
            .skip(page * perPage)
            .limit(perPage)
            .into(new ArrayList<>());

        long total = controlPlane
            .collection("tenants", TenantEntity.class)
            .countDocuments(filter);

        return new Pagination<>(
            total,
            (int) Math.ceil((double) total / perPage),
            (long) perPage,
            new String[]{ "created_at" },
            "desc",
            page,
            page + 1,
            Math.max(0, page - 1),
            Map.of(),
            items.stream().map(TenantMapper::toSummaryDto).toList()
        );
    }
}
```

### 12.2 Accès cross-tenant — support de niveau 2 et impersonation

Quand un client NOMMEE 1 ouvre un ticket de support et qu'Hervé doit voir le bordereau d'achat n°2473 pour debugger un problème, il a besoin d'accéder à la donnée du tenant sans en être membre. Cet accès doit être :

- Restreint au rôle `PLATFORM_ADMIN`
- Audité systématiquement (qui, quand, quel tenant, pour quel motif)
- Visible côté client via un bandeau "Support éditeur actif"

```java
@ApplicationScoped
public class CrossTenantQueryService {

    @Inject MongoClient mongoClient;
    @Inject ControlPlaneProvider controlPlane;
    @Inject AuditLogger audit;
    @Inject TenantContext tenantContext;

    /**
     * Active une "session support" sur un tenant donné pour la durée d'une opération.
     * Tout accès est tracé dans cabosse_control.global_audit.
     */
    @RolesAllowed(Roles.PLATFORM_ADMIN)
    public <T> T withTenantDatabase(UUID tenantId, String reason,
                                     Function<MongoDatabase, T> action) {
        TenantEntity tenant = resolveTenant(tenantId);

        audit.log(GlobalAuditEntry.of(
            AuditEventType.CROSS_TENANT_ACCESS,
            tenantContext.userId(),
            tenantId,
            reason
        ));

        return action.apply(mongoClient.getDatabase(tenant.databaseName));
    }

    private TenantEntity resolveTenant(UUID id) {
        TenantEntity tenant = controlPlane
            .collection(ControlPlane.Collections.TENANTS, TenantEntity.class)
            .find(Filters.eq("_id", id))
            .first();
        if (tenant == null) throw new NotFoundException("tenant");
        return tenant;
    }
}
```

**Usage :**

```java
@RolesAllowed(Roles.PLATFORM_ADMIN)
public PurchaseOrderResponseDto inspectPurchaseOrder(
        UUID tenantId, UUID orderId, String reason) {

    PurchaseOrderEntity entity = crossTenantQuery.withTenantDatabase(
        tenantId, reason,
        db -> db.getCollection("purchase_orders", PurchaseOrderEntity.class)
            .find(Filters.eq("_id", orderId))
            .first()
    );

    if (entity == null) throw new NotFoundException("purchase_order");
    return PurchaseOrderMapper.toDto(entity);
}
```

**Interdiction** : aucun service métier (M1 à M8) ne doit injecter `CrossTenantQueryService`. Son usage est réservé aux contrôleurs et services M9.

### 12.3 Impersonation utilisateur

Pour les cas où Hervé doit "se mettre dans la peau" d'un utilisateur du tenant (debug d'un workflow utilisateur précis), un endpoint dédié émet un JWT d'impersonation :

```java
@RolesAllowed(Roles.PLATFORM_ADMIN)
@Path("/api/v1/admin/impersonate")
public class ImpersonationResource {

    @Inject ImpersonationService service;

    @POST
    public Response impersonate(ImpersonationRequestDto request) {
        ImpersonationTokenDto token = service.issue(
            request.userId(),
            request.reason(),
            Duration.ofMinutes(30)
        );
        return Response.ok(new ApiResponse<>(200, "OK", token)).build();
    }
}
```

Le JWT émis porte un claim supplémentaire `impersonatedBy` (le `userId` du super-admin plateforme). Le frontend détecte ce claim et affiche un bandeau orange persistant "Vous opérez en mode support pour [tenant]". Le journal d'audit enregistre toute opération réalisée pendant cette session, taggée `impersonatedBy`.

---

## 13. Backup, restauration, suppression

### 13.1 Backup par tenant — script shell

```bash
#!/bin/bash
# /opt/neiba/scripts/backup-tenant.sh
set -euo pipefail

TENANT_DB=$1
BACKUP_ROOT="/backups/$(date +%F)"

mkdir -p "${BACKUP_ROOT}/${TENANT_DB}"

mongodump \
    --uri="${MONGO_URI}" \
    --db="${TENANT_DB}" \
    --gzip \
    --out="${BACKUP_ROOT}/${TENANT_DB}"

# Upload vers S3-compatible
aws s3 sync "${BACKUP_ROOT}/${TENANT_DB}" \
    "s3://neiba-backups/${TENANT_DB}/$(date +%F)/" \
    --endpoint-url="${S3_ENDPOINT}"
```

Orchestration par cron, **un job par tenant**, fréquence selon le plan tarifaire (quotidien pour Pro, horaire pour Enterprise).

### 13.2 Politique de rétention

| Plan | Fréquence | Rétention |
|---|---|---|
| Free | Hebdomadaire | 30 jours |
| Pro | Quotidien | 90 jours |
| Enterprise | Horaire | 365 jours + archivage à froid annuel |

### 13.3 Restauration ciblée

```bash
mongorestore \
    --uri="${MONGO_URI}" \
    --nsInclude="tenant_a3f12c40b8f44d7e9af1c83e6b2d1f9a.*" \
    --drop \
    --gzip \
    "/restores/2026-05-15/tenant_a3f12c40b8f44d7e9af1c83e6b2d1f9a"
```

Un seul tenant restauré, sans toucher aux autres. Le `--drop` recrée à neuf, à utiliser uniquement après confirmation client (sinon `mongorestore` sans `--drop` pour merger).

### 13.4 Suppression d'un tenant — droit à l'effacement (RGPD)

```java
@ApplicationScoped
public class TenantDeletionService {

    @Inject MongoClient mongoClient;
    @Inject ControlPlaneProvider controlPlane;
    @Inject ArchiveService archiveService;
    @Inject AuditLogger audit;

    @RolesAllowed(Roles.PLATFORM_ADMIN)
    public void delete(UUID tenantId, String reason) {
        TenantEntity tenant = resolveTenant(tenantId);

        // 1. Archivage légal (OHADA : conservation 10 ans des pièces comptables)
        archiveService.archive(tenant.databaseName, reason);

        // 2. Drop de la base
        mongoClient.getDatabase(tenant.databaseName).drop();

        // 3. Marquage dans le control plane (on conserve l'entrée tenant pour traçabilité)
        controlPlane.collection(ControlPlane.Collections.TENANTS, TenantEntity.class).updateOne(
            Filters.eq("_id", tenantId),
            Updates.combine(
                Updates.set("status", TenantStatus.DELETED.name()),
                Updates.set("deleted_at", Instant.now()),
                Updates.set("deletion_reason", reason)
            )
        );

        // 4. Désactivation immédiate des utilisateurs
        controlPlane.collection(ControlPlane.Collections.USERS, UserEntity.class).updateMany(
            Filters.eq("tenant_id", tenantId),
            Updates.set("status", UserStatus.DISABLED.name())
        );

        // 5. Audit
        audit.log(GlobalAuditEntry.of(
            AuditEventType.TENANT_DELETED,
            tenantContext.userId(),
            tenantId,
            reason
        ));
    }
}
```

**Une opération atomique côté MongoDB** : `db.dropDatabase()`. Aucun `DELETE ... WHERE tenant_id = ?` qui pourrait planter à mi-chemin. La donnée n'existe plus, physiquement, immédiatement.

---

## 14. Tests

### 14.1 Tests unitaires

Les services et repositories tenant-scoped sont testés en mockant les providers :

```java
@QuarkusTest
class PurchaseOrderServiceTest {

    @InjectMock TenantMongoDatabaseProvider db;
    @InjectMock TenantContext tenantContext;
    @Inject PurchaseOrderService service;

    @Test
    void shouldCreatePurchaseOrder() {
        MongoCollection<PurchaseOrderEntity> coll = mock(MongoCollection.class);
        when(db.collection("purchase_orders", PurchaseOrderEntity.class))
            .thenReturn(coll);
        when(tenantContext.userId()).thenReturn(SOME_USER_UUID);

        service.create(/* DTO */);

        verify(coll).insertOne(any(PurchaseOrderEntity.class));
    }
}
```

### 14.2 Tests d'intégration

**Testcontainers MongoDB en mode replica set obligatoire**, parce que les transactions exigent un replica set et qu'il faut tester sur la même topologie qu'en prod.

```java
@QuarkusIntegrationTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class TenantProvisioningIT {

    @Inject TenantProvisioningService service;
    @Inject MongoClient mongoClient;
    @Inject ControlPlaneProvider controlPlane;

    @Test
    void shouldProvisionFullTenant() {
        CreateTenantRequestDto request = new CreateTenantRequestDto(
            "Coopérative Test", "coop-test",
            "admin@coop-test.ci", "Aïcha", "Diabaté",
            "free"
        );

        TenantId result = service.provision(request);

        // 1. Tenant enregistré dans le control plane
        TenantEntity tenant = controlPlane
            .collection("tenants", TenantEntity.class)
            .find(Filters.eq("_id", result.value()))
            .first();
        assertThat(tenant).isNotNull();
        assertThat(tenant.status).isEqualTo(TenantStatus.ACTIVE);

        // 2. Base physique créée
        boolean dbExists = StreamSupport.stream(
            mongoClient.listDatabaseNames().spliterator(), false
        ).anyMatch(n -> n.equals(tenant.databaseName));
        assertThat(dbExists).isTrue();

        // 3. Collections de référentiels seedées
        long unitCount = mongoClient.getDatabase(tenant.databaseName)
            .getCollection("units").countDocuments();
        assertThat(unitCount).isGreaterThan(0);

        // 4. Admin créé en statut INVITED
        UserEntity admin = controlPlane
            .collection("users", UserEntity.class)
            .find(Filters.eq("email", "admin@coop-test.ci"))
            .first();
        assertThat(admin).isNotNull();
        assertThat(admin.status).isEqualTo(UserStatus.INVITED);
    }
}
```

### 14.3 Ressource de test Testcontainers (replica set)

```java
public class MongoReplicaSetTestResource
        implements QuarkusTestResourceLifecycleManager {

    private MongoDBContainer container;

    @Override
    public Map<String, String> start() {
        container = new MongoDBContainer("mongo:7.0")
            .withReuse(true);
        container.start();
        return Map.of(
            "quarkus.mongodb.connection-string", container.getReplicaSetUrl()
        );
    }

    @Override
    public void stop() {
        if (container != null) container.stop();
    }
}
```

---

## 15. Règles de référence

À conserver à jour dans `CLAUDE.md` (section 7 du document général).

| ❌ Interdit | ✅ Attendu |
|---|---|
| Injection directe de `MongoClient` dans un service métier | Passer par `ControlPlaneProvider` ou `TenantMongoDatabaseProvider` |
| Champ `tenant_id` sur une entité tenant-scoped | La base **est** le scope, aucun champ tenant nécessaire |
| Pattern `id ObjectId` + `publicId UUID` séparés sur une entité | Un seul `UUID` v7 en `_id`, généré via `IdGenerator` |
| `UUID.randomUUID()` direct dans un service ou repository | `IdGenerator.newId()` injecté |
| Référence par `xxx_public_id` en BSON | Référence par `xxx_id` (correspond au `_id` de l'entité cible) |
| `@MongoEntity` ou Panache active record sur une entité tenant-scoped | POJO simple, repository explicite via le provider |
| Lecture de `databaseName` ailleurs que dans `TenantContext` | Le `TenantContext` est l'unique source |
| `CrossTenantQueryService` injecté dans un service M1–M8 | Réservé aux contrôleurs et services M9, audité |
| Endpoint M9 sans `@RolesAllowed(Roles.PLATFORM_ADMIN)` | Tout endpoint d'administration plateforme est protégé explicitement |
| String littérale pour rôle, événement, claim, base contrôle, type d'audit, nom de cache | Constante depuis `Roles`, `Events`, `JwtClaims`, `ControlPlane`, `AuditEventType`, `CacheNames` |
| `new GlobalAuditEntry(...)` direct avec event_type string | `GlobalAuditEntry.of(AuditEventType.X, ...)` avec enum typé |
| Job `@Scheduled` qui appelle un repository tenant-scoped sans `@ActivateRequestContext` | Toujours via `TenantAwareExecutor.runForTenant(...)` |
| Migration appliquée manuellement avec `mongosh` | Toujours via Mongock, jamais d'écriture schéma hors versioning |
| Suppression par script ad hoc d'un tenant | Toujours via `TenantDeletionService` (archivage + drop + audit) |
| Backup global du cluster pour "restaurer un tenant" | Toujours `mongodump --db <tenant_db>`, restauration ciblée |
| H2 ou MongoDB standalone pour les tests d'intégration | Testcontainers MongoDB en mode replica set |
| `tenantContext.tenantId()` appelé hors d'une requête HTTP non protégée | Échec délibéré avec `IllegalStateException` explicite |

---

## Annexe — diagramme de séquence d'une requête

```
Client            JAX-RS           TenantContext       Service       Repository    Provider          MongoDB
  │                  │                    │              │              │              │                │
  │  GET /api/v1/    │                    │              │              │              │                │
  │  purchases       │                    │              │              │              │                │
  ├─────────────────▶│                    │              │              │              │                │
  │                  │                    │              │              │              │                │
  │                  │ JwtFilter          │              │              │              │                │
  │                  │ (valide token)     │              │              │              │                │
  │                  │                    │              │              │              │                │
  │                  │ TenantContextFilter│              │              │              │                │
  │                  │ initialize(...)    │              │              │              │                │
  │                  ├───────────────────▶│              │              │              │                │
  │                  │                    │              │              │              │                │
  │                  │ TenantStatusGuard  │              │              │              │                │
  │                  │ (vérifie ACTIVE)   │              │              │              │                │
  │                  │                    │              │              │              │                │
  │                  │ handle(request)    │              │              │              │                │
  │                  ├──────────────────────────────────▶│              │              │                │
  │                  │                    │              │ list(...)    │              │                │
  │                  │                    │              ├─────────────▶│              │                │
  │                  │                    │              │              │ coll()       │                │
  │                  │                    │              │              ├─────────────▶│                │
  │                  │                    │              │              │              │ database()     │
  │                  │                    │              │              │              │ → tenant_a3f.. │
  │                  │                    │ databaseName()              │              │                │
  │                  │                    │◀─────────────────────────────────────────  │                │
  │                  │                    │              │              │              │                │
  │                  │                    │              │              │              │ find(...)      │
  │                  │                    │              │              │              ├───────────────▶│
  │                  │                    │              │              │              │                │
  │                  │                    │              │              │              │ results        │
  │                  │                    │              │              │              │◀───────────────┤
  │                  │                    │              │              │ results      │                │
  │                  │                    │              │              │◀─────────────┤                │
  │                  │                    │              │ DTO list     │              │                │
  │                  │                    │              │◀─────────────┤              │                │
  │                  │                    │              │              │              │                │
  │  200 OK          │                    │              │              │              │                │
  │  ApiResponse<...>│                    │              │              │              │                │
  │◀─────────────────┤                    │              │              │              │                │
```

---

**Fin du document.**

*NEIBA Technologies · Cabosse ERP · Mai 2026 · v1.0*
