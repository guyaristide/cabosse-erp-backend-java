# Constantes partagées — Cabosse ERP

**Référence** : NEIBA-ARCH-2026-002 · Mai 2026
**Statut** : socle non négociable, à respecter dans tout le code Java backend.

Inventaire complet des constantes sémantiques partagées entre plusieurs composants du backend Cabosse ERP. Ce document est la **source de vérité** : toute chaîne référencée depuis plus d'une classe doit être déclarée ici.

---

## 1. Pourquoi

Une "magic string" est une valeur sémantique écrite en littéral dans le code (`"TENANT_ADMIN"`, `"tenant.provisioned"`, `"tenantId"`). Un seul typo crée un bug silencieux :

- `@RolesAllowed("TENAN_ADMIN")` → l'endpoint devient inaccessible, aucune erreur n'est levée à la compilation ni au démarrage.
- `eventBus.publish("tenant.provisionned", ...)` → l'événement n'est jamais consommé, le mail d'invitation ne part jamais.
- `@Claim("tenantID")` → la valeur lue dans le JWT est `null`, le `TenantContext` n'est pas hydraté, et tous les endpoints qui en dépendent partent en `IllegalStateException` au runtime.

Le compilateur Java ne peut pas vérifier le contenu d'une chaîne littérale. Il vérifie en revanche **toute référence à une constante** : un typo sur `Roles.TENAN_ADMIN` au lieu de `Roles.TENANT_ADMIN` provoque un échec de compilation immédiat.

**Règle** : si une chaîne sémantique est référencée par deux classes ou plus, elle est déclarée comme constante dans ce document.

---

## 2. Organisation des packages

```
com.ntech.cabosse.shared/
  ├── security/
  │   ├── Roles.java
  │   └── JwtClaims.java
  ├── events/
  │   └── Events.java
  ├── audit/
  │   └── AuditEventType.java
  └── persistence/
      ├── ControlPlane.java
      └── CacheNames.java
```

Tout sous `shared.*` parce que ces constantes sont **transversales** aux features. Aucune feature métier (`purchase`, `production`, `sales`, etc.) n'est propriétaire de ces constantes.

---

## 3. `Roles` — rôles utilisateurs

```java
package com.ntech.cabosse.shared.security;

import java.util.Set;

/**
 * Rôles applicatifs Cabosse ERP. Les valeurs sont utilisées :
 *   - dans @RolesAllowed (annotations JAX-RS / Quarkus)
 *   - dans les claims JWT (groupe MicroProfile)
 *   - dans les vérifications runtime (Set.contains, etc.)
 *
 * Toute string littérale référençant un rôle ailleurs dans le code est interdite.
 */
public final class Roles {

    private Roles() {}

    /** Super-admin de la plateforme. Accès au back-office d'administration (M9). */
    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";

    /** Administrateur d'un tenant. Gère les utilisateurs, rôles, paramètres du tenant. */
    public static final String TENANT_ADMIN = "TENANT_ADMIN";

    /** Utilisateur standard d'un tenant. Accès limité par les rôles métier. */
    public static final String USER = "USER";

    /** Compte technique pour les jobs et événements internes. Non assignable à un humain. */
    public static final String SYSTEM = "SYSTEM";

    public static final Set<String> ALL = Set.of(
        PLATFORM_ADMIN, TENANT_ADMIN, USER, SYSTEM
    );

    /** Rôles assignables à un utilisateur humain (exclut SYSTEM). */
    public static final Set<String> HUMAN_ASSIGNABLE = Set.of(
        PLATFORM_ADMIN, TENANT_ADMIN, USER
    );

    public static boolean isKnown(String role) {
        return ALL.contains(role);
    }
}
```

### Usage

```java
// Annotations JAX-RS
@RolesAllowed(Roles.PLATFORM_ADMIN)
public Response inspectTenant(...) { ... }

// Assignation
admin.roles = Set.of(Roles.TENANT_ADMIN);

// Vérification
if (user.roles.contains(Roles.PLATFORM_ADMIN)) { ... }

// JWT
.groups(Set.of(Roles.TENANT_ADMIN, Roles.USER))
```

---

## 4. `JwtClaims` — noms des claims JWT custom

```java
package com.ntech.cabosse.shared.security;

/**
 * Noms des claims JWT custom (hors claims standard MP-JWT : sub, upn, groups, iss, exp).
 *
 * Utilisés à l'émission (JwtIssuer) et à la lecture (TenantContextFilter, intercepteurs).
 * Tout typo entre l'émission et la lecture rend le claim invisible au runtime.
 */
public final class JwtClaims {

    private JwtClaims() {}

    /** UUID public du tenant courant. */
    public static final String TENANT_ID = "tenantId";

    /** Nom de la base MongoDB du tenant courant, embarqué dans le JWT pour éviter
     *  une lecture du control plane à chaque requête. */
    public static final String TENANT_DATABASE_NAME = "tenantDatabaseName";

    /** UUID du super-admin plateforme actif lors d'une session d'impersonation.
     *  Présent uniquement sur les JWT d'impersonation. */
    public static final String IMPERSONATED_BY = "impersonatedBy";
}
```

### Usage

```java
// Émission JWT
Jwt.issuer(issuer)
   .subject(user.id.toString())
   .claim(JwtClaims.TENANT_ID, tenant.id.toString())
   .claim(JwtClaims.TENANT_DATABASE_NAME, tenant.databaseName)
   .sign();

// Lecture par injection
@Inject @Claim(JwtClaims.TENANT_ID) ClaimValue<String> tenantIdClaim;
@Inject @Claim(JwtClaims.TENANT_DATABASE_NAME) ClaimValue<String> dbNameClaim;
```

---

## 5. `Events` — topics de l'event bus

```java
package com.ntech.cabosse.shared.events;

/**
 * Adresses (topics) de l'event bus Vert.x utilisé par Quarkus.
 *
 * Convention de nommage : <domaine>.<événement> en kebab-case, snake_case interne.
 *   - "tenant.provisioned"
 *   - "purchase.order.confirmed"
 *
 * Toute string littérale dans .publish() ou @ConsumeEvent en dehors de cette classe est interdite.
 */
public final class Events {

    private Events() {}

    // === Cycle de vie tenant ===
    public static final String TENANT_PROVISIONED = "tenant.provisioned";
    public static final String TENANT_SUSPENDED   = "tenant.suspended";
    public static final String TENANT_REACTIVATED = "tenant.reactivated";
    public static final String TENANT_DELETED     = "tenant.deleted";

    // === Cycle de vie utilisateur ===
    public static final String USER_INVITED       = "user.invited";
    public static final String USER_ACTIVATED     = "user.activated";
    public static final String USER_PASSWORD_RESET_REQUESTED = "user.password.reset_requested";

    // === Achats ===
    public static final String PURCHASE_ORDER_CONFIRMED = "purchase.order.confirmed";
    public static final String PURCHASE_ORDER_RECEIVED  = "purchase.order.received";

    // === Production ===
    public static final String PRODUCTION_ORDER_STARTED  = "production.order.started";
    public static final String PRODUCTION_ORDER_COMPLETED = "production.order.completed";

    // === Stocks ===
    public static final String STOCK_LOW_THRESHOLD = "stock.low_threshold";

    // === Ventes ===
    public static final String SALES_ORDER_PLACED   = "sales.order.placed";
    public static final String SALES_ORDER_SHIPPED  = "sales.order.shipped";
}
```

### Usage

```java
// Émission
eventBus.publish(Events.TENANT_PROVISIONED, payload);

// Consommation
@ConsumeEvent(Events.TENANT_PROVISIONED)
public void onTenantProvisioned(TenantProvisionedEvent event) { ... }
```

---

## 6. `AuditEventType` — types d'événements d'audit

Pas une classe de constantes, un **enum**. Les types d'audit ne sont jamais utilisés dans une annotation (qui exige une constante de compilation), donc on profite du type-safe et de l'autocomplete IDE.

```java
package com.ntech.cabosse.shared.audit;

/**
 * Types d'événements enregistrés dans neiba_control.global_audit.
 *
 * Sérialisation MongoDB : enum.name() (string en BSON).
 */
public enum AuditEventType {

    // === Tenant lifecycle ===
    TENANT_PROVISIONED,
    TENANT_PROVISIONING_FAILED,
    TENANT_SUSPENDED,
    TENANT_REACTIVATED,
    TENANT_DELETED,

    // === Sécurité ===
    PERMISSION_DENIED,
    AUTHENTICATION_FAILED,
    PASSWORD_CHANGED,

    // === Support / cross-tenant ===
    CROSS_TENANT_ACCESS,
    IMPERSONATION_STARTED,
    IMPERSONATION_ENDED,

    // === Configuration ===
    ROLE_GRANTED,
    ROLE_REVOKED,
    USER_DISABLED;
}
```

### Usage

```java
// Création
GlobalAuditEntry entry = GlobalAuditEntry.of(
    AuditEventType.CROSS_TENANT_ACCESS,
    tenantContext.userId(),
    targetTenantPublicId,
    reason
);
audit.log(entry);

// Filtrage en lecture
auditCollection.find(Filters.eq("event_type", AuditEventType.IMPERSONATION_STARTED.name()))
```

---

## 7. `ControlPlane` — base et collections du plan contrôle

```java
package com.ntech.cabosse.shared.persistence;

/**
 * Nom de la base MongoDB du plan contrôle et de ses collections.
 *
 * Le nom de la base est fixé (pas de variation par environnement) parce qu'il ne change
 * pas entre dev, qa et prod. Les noms tenant en revanche utilisent un préfixe configurable
 * (voir ApplicationConfig.tenantDatabasePrefix).
 */
public final class ControlPlane {

    private ControlPlane() {}

    public static final String DATABASE = "cabosse_control";

    public static final class Collections {

        private Collections() {}

        public static final String TENANTS         = "tenants";
        public static final String USERS           = "users";
        public static final String SUBSCRIPTIONS   = "subscriptions";
        public static final String GLOBAL_AUDIT    = "global_audit";
        public static final String SUPPORT_TICKETS = "support_tickets";
        public static final String PLANS           = "plans";
    }
}
```

### Usage

```java
// Panache active record (control plane only)
@MongoEntity(database = ControlPlane.DATABASE, collection = ControlPlane.Collections.TENANTS)
public class TenantEntity extends PanacheMongoEntityBase { ... }

// Accès brut
mongoClient.getDatabase(ControlPlane.DATABASE)
    .getCollection(ControlPlane.Collections.GLOBAL_AUDIT, GlobalAuditEntry.class);
```

---

## 8. `CacheNames` — noms des caches Quarkus

```java
package com.ntech.cabosse.shared.persistence;

/**
 * Noms des caches déclarés dans application.yml (quarkus.cache.caffeine.<name>) et
 * référencés dans @CacheResult / @CacheInvalidate / @CacheKey.
 *
 * Tout cache configuré dans application.yml doit avoir sa constante ici.
 */
public final class CacheNames {

    private CacheNames() {}

    /** Cache du statut des tenants (TTL : 5 min). Utilisé par TenantStatusGuard. */
    public static final String TENANT_REGISTRY = "tenant-registry";

    /** Cache des permissions calculées par rôle (TTL : 10 min). */
    public static final String ROLE_PERMISSIONS = "role-permissions";
}
```

### Usage

```java
@CacheResult(cacheName = CacheNames.TENANT_REGISTRY)
public TenantStatus statusOf(UUID tenantPublicId) { ... }
```

Et dans `application.yml` :

```yaml
quarkus:
  cache:
    caffeine:
      tenant-registry:           # ← doit matcher CacheNames.TENANT_REGISTRY
        expire-after-write: 5M
        maximum-size: 1000
      role-permissions:
        expire-after-write: 10M
        maximum-size: 500
```

> **Limite** : le nom du cache dans `application.yml` reste une chaîne YAML, donc dupliquée. Un test de cohérence au démarrage vérifie que toute valeur de `CacheNames` est bien déclarée côté config (voir section 10).

---

## 9. Ce qu'on n'inclut PAS dans ces classes

Pour rester pragmatique, on ne centralise **pas** :

- **Noms de collections tenant-scoped** (`purchase_orders`, `production_orders`, etc.) : chaque collection est touchée par un seul repository. On la déclare en `private static final String COLLECTION = "purchase_orders"` dans ce repository.
- **Champs MongoDB** dans les filtres (`Filters.eq("supplier_id", x)`) : référencés depuis le seul repository qui touche la collection. Pas de gain à centraliser.
- **Clés de configuration** : déjà typées via `@ConfigMapping`. Pas besoin de constantes en plus.
- **Statuts de tenant / user** : déjà des `enum` Java (`TenantStatus`, `UserStatus`). Type-safe par construction.

La règle de décision : **si une chaîne est référencée par deux classes ou plus, elle est constante. Sinon, elle est inline.**

---

## 10. Vérification de cohérence au démarrage

Un check `@Observes StartupEvent` valide que :

1. Tous les caches déclarés dans `CacheNames` existent dans la configuration Quarkus.
2. Tous les rôles assignés à un utilisateur dans la base (sur la collection `users`) sont connus de `Roles.ALL`.

```java
@ApplicationScoped
public class ConstantsConsistencyCheck {

    @Inject CacheManager cacheManager;
    @Inject UserRepository userRepository;
    @Inject Logger log;

    void onStart(@Observes StartupEvent ev) {
        checkCacheNames();
        checkRoles();
    }

    private void checkCacheNames() {
        for (Field f : CacheNames.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
                try {
                    String name = (String) f.get(null);
                    if (cacheManager.getCache(name).isEmpty()) {
                        throw new IllegalStateException(
                            "Cache '" + name + "' référencé dans CacheNames mais "
                          + "absent de application.yml");
                    }
                } catch (IllegalAccessException e) {
                    // ne devrait pas arriver
                }
            }
        }
    }

    private void checkRoles() {
        long unknownCount = userRepository.streamAll()
            .flatMap(u -> u.roles.stream())
            .filter(r -> !Roles.isKnown(r))
            .distinct()
            .peek(r -> log.errorf("Rôle inconnu en base : %s", r))
            .count();
        if (unknownCount > 0) {
            throw new IllegalStateException(
                unknownCount + " rôles inconnus détectés en base. "
              + "Migration de rôles ou nettoyage requis.");
        }
    }
}
```

Le démarrage **échoue** si une incohérence est détectée. Pas de runtime surprise.

---

## 11. Règle de discipline

| ❌ Interdit | ✅ Attendu |
|---|---|
| `@RolesAllowed("PLATFORM_ADMIN")` | `@RolesAllowed(Roles.PLATFORM_ADMIN)` |
| `Set.of("TENANT_ADMIN")` | `Set.of(Roles.TENANT_ADMIN)` |
| `eventBus.publish("tenant.provisioned", e)` | `eventBus.publish(Events.TENANT_PROVISIONED, e)` |
| `@ConsumeEvent("purchase.order.confirmed")` | `@ConsumeEvent(Events.PURCHASE_ORDER_CONFIRMED)` |
| `@Claim("tenantId")` | `@Claim(JwtClaims.TENANT_ID)` |
| `.claim("tenantDatabaseName", db)` | `.claim(JwtClaims.TENANT_DATABASE_NAME, db)` |
| `audit.log(new GlobalAuditEntry("CROSS_TENANT_ACCESS", ...))` | `audit.log(GlobalAuditEntry.of(AuditEventType.CROSS_TENANT_ACCESS, ...))` |
| `mongoClient.getDatabase("cabosse_control")` | `mongoClient.getDatabase(ControlPlane.DATABASE)` |
| `controlPlane.getCollection("tenants", T.class)` | `controlPlane.getCollection(ControlPlane.Collections.TENANTS, T.class)` |
| `@CacheResult(cacheName = "tenant-registry")` | `@CacheResult(cacheName = CacheNames.TENANT_REGISTRY)` |

---

**Fin du document.**

*NEIBA Technologies · Cabosse ERP · Mai 2026 · v1.0*
