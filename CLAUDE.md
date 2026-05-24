# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

Run from this directory. Dev runs **standalone, sans Docker** (délibéré — décision memo §3.5).

```bash
./gradlew quarkusDev              # dev avec live reload — Dev UI http://localhost:8080/q/dev/
./gradlew build                   # produit build/quarkus-app/quarkus-run.jar (pas un uber-jar)
./gradlew test                    # JUnit 5 + RestAssured
./gradlew test --tests com.ntech.cabosse.SomeTest           # classe unique
./gradlew test --tests "com.ntech.cabosse.SomeTest.method"  # méthode unique
./gradlew build -Dquarkus.package.jar.type=uber-jar          # variante uber-jar
./gradlew build -Dquarkus.native.enabled=true                # build natif GraalVM
```

PostgreSQL 16 + JDK 21 installés nativement. Docker Compose réservé à QA et production.

## Contraintes d'architecture (memo NEIBA-TECH-2026-001)

- **Config** : YAML uniquement (`src/main/resources/application.yml`). Jamais de `.properties`.
- **Build** : Gradle uniquement (Kotlin DSL cible ; squelette en Groovy DSL — préserver la cohérence).
- **Package root** : `com.ntech.cabosse`.
- **Persistence** : Hibernate ORM Panache sur PostgreSQL 16. Remplacer `quarkus-mongodb-panache` / `quarkus-liquibase-mongodb` du starter par `quarkus-hibernate-orm-panache` + `quarkus-jdbc-postgresql` + `quarkus-liquibase`.
- **Migrations** : Liquibase avec changelogs **YAML** (pas XML ni SQL).
- **Réactif** : Hibernate Reactive uniquement sur les endpoints haute-concurrence (exports, streams). Défaut : Panache bloquant.
- **Multi-tenancy** : colonne discriminator (Hibernate natif) + **PostgreSQL Row-Level Security obligatoire comme second verrou**. Ne jamais s'appuyer sur le discriminator seul.
- **Auth** : Quarkus OIDC direct (pas de broker Keycloak au MVP). MFA délégué au provider OIDC.
- **Contrat API** : REST + OpenAPI. Le client TypeScript frontend est **généré depuis le schéma OpenAPI** — traiter l'output OpenAPI comme un contrat, pas un effet de bord.
- **Argent** : `BigDecimal` (+ JSR-354 Money si utile). Devise primaire : FCFA (XOF). Jamais de `double` / `float` sur un champ monétaire.
- **Audit** : table dédiée, payload JSONB.
- **Java** : 21. `-parameters` activé.

---

## Règles imposées — à respecter sans exception

### 1 · Aucun nom de marque dans le modèle de domaine

Les identifiants Java (champs d'entité, noms de type, méthodes, variables) doivent être **neutres vis-à-vis de la marque**.

```java
// ❌
boolean isNeibaStaff;
String cabosseId;
boolean detectNeibaStaff(String email)

// ✅
boolean isPlatformAdmin;
PlatformRole platformRole;
boolean detectPlatformAdmin(String email)
```

Les chaînes de marque ("NEIBA Technologies", "Cabosse") n'apparaissent que comme **valeurs** : configuration, mock data, réponses JSON de présentation — jamais comme noms de colonne, noms de champ d'entité, ni noms de méthode de service.

### 2 · La plateforme est filière-agnostique

Le produit s'adresse à toute unité de **transformation matière première → produit fini** : hévéa, cacao, savon, manioc, etc. Les noms de colonne, de table et de paramètre doivent être génériques (`activite_type`, pas `filiere_cacao`).

### 3 · La configuration métier appartient au tenant

Les paramètres comme secteur d'activité, devise, plan comptable, classification filière sont des champs du tenant (table `tenant_config`), jamais des paramètres de transaction répétés à chaque appel.

### 4 · Backoffice réservé aux @neiba-technologies.com

La détection `isPlatformAdmin` se fait sur le domaine email en phase MVP :

```java
boolean detectPlatformAdmin(String email) {
    return email != null && email.endsWith("@neiba-technologies.com");
}
```

Le domaine est une valeur dans l'implémentation. Ne pas le mettre dans la signature ni dans le modèle.

### 5 · Argent — jamais de double/float

Tout champ monétaire utilise `BigDecimal`. Colonnes SQL : `NUMERIC(18, 2)`. Jamais `FLOAT`, `DOUBLE PRECISION`, `REAL`.

### 6 · RLS est non-négociable

Chaque table métier doit avoir une politique RLS `tenant_id = current_setting('app.tenant_id')`. Un oubli du filtre discriminator dans une requête doit être rattrapé par RLS. Ne jamais désactiver RLS sur une table de données client.

### 7 · CMUP transactionnel

Le recalcul du coût moyen unitaire pondéré se fait dans une transaction sérialisable. Jamais en lecture-modification-écriture non atomique.

## Langue

Identifiants et commits en anglais. Commentaires, messages d'erreur API retournés au frontend, et logs en français. Les clés i18n générées par le backend (messages de validation, libellés d'enum) sont en français.

# Conventions backend Java Quarkus — NEIBA Technologies

Ce document définit les règles **non négociables** à respecter sur tout projet backend Java Quarkus. Toute génération, modification ou refactorisation de code doit s'y conformer. En cas de doute ou de conflit avec une règle, demander confirmation avant d'agir.

---

## 1. Build et outillage

- **Build tool** : Gradle exclusivement. Ne jamais générer, proposer ou convertir vers Maven sauf demande explicite.

---

## 2. Configuration

### 2.1 Format des fichiers
- Tous les fichiers de configuration sont en `.yml` ou `.yaml`.
- **Interdit** : `.properties`, sous quelque forme que ce soit.

### 2.2 Un fichier par environnement
- Un fichier distinct par environnement : `application.yml`, `application-dev.yml`, `application-qa.yml`, `application-prod.yml`, etc.
- **Interdit** : un fichier unique qui regroupe tous les environnements via les préfixes `%dev`, `%qa`, `%prod`.

### 2.3 Configurations métier typées

Toute configuration custom doit être déclarée via une interface annotée `@ConfigMapping` :

```java
@ConfigMapping(prefix = "application")
public interface ApplicationConfig {

    @WithName("upload_dir")
    String uploadDir();
}
```

- Ne jamais lire une propriété directement dans une classe métier (pas de `@ConfigProperty`, pas de `ConfigProvider.getConfig()`).
- Toujours injecter l'interface de configuration typée.

### 2.4 Secrets et variables sensibles
- Aucun secret en dur dans le code ou dans un fichier `application.yml` versionné (clés JWT, mots de passe DB, tokens d'API tiers, etc.).
- Toute valeur sensible passe par variable d'environnement ou Vault.
- Le `application.yml` versionné référence la variable : `quarkus.datasource.password: ${DB_PASSWORD}`.

### 2.5 CORS
- Configuration CORS explicite par environnement dans `application-{env}.yml`.
- Jamais d'origines `*` en production. Liste blanche stricte.

---

## 3. Style de code et organisation

### 3.1 Style général
- **Pas d'inner classes.** Toute classe imbriquée doit être extraite dans son propre fichier.
- **Pas de Lombok.** Utiliser `record` quand approprié, sinon écrire explicitement constructeurs, accesseurs, `equals`, `hashCode`.
- **Nommage strictement camelCase**, jamais `snake_case`, partout (champs, paramètres, JSON sérialisé).

### 3.2 Organisation des packages

Organisation par **feature**, pas par couche technique :

```
com.neiba.cabosse.purchase
  ├── controller
  ├── service
  ├── repository
  ├── entity
  └── dto
```

**Interdit** : `com.neiba.cabosse.controllers.purchase`, `com.neiba.cabosse.services.purchase`. Le couplage est de proximité métier, pas de proximité technique.

### 3.3 Dates et heures
- `java.time` uniquement : `Instant` (stockage UTC), `LocalDate` / `LocalDateTime` (quand le fuseau a un sens métier), `ZonedDateTime` aux frontières si nécessaire.
- **Interdit** ailleurs qu'aux interfaces JDBC bas niveau : `java.util.Date`, `Calendar`, `Timestamp`.

### 3.4 Constantes partagées (aucune magic string)

Aucune chaîne sémantique partagée entre plusieurs classes ne doit apparaître sous forme de littéral. Cela couvre :

- les **rôles** (`PLATFORM_ADMIN`, `TENANT_ADMIN`, etc.) → classe `Roles`
- les **topics d'event bus** (`tenant.provisioned`, etc.) → classe `Events`
- les **claims JWT custom** (`tenantId`, etc.) → classe `JwtClaims`
- les **types d'audit** → enum `AuditEventType`
- les **bases et collections du plan contrôle** → classe `ControlPlane`
- les **noms de cache** → classe `CacheNames`

Toutes ces classes vivent sous `tech.neiba.cabosse.shared.*`. Voir le document `shared-constants.md` (NEIBA-ARCH-2026-002) pour l'inventaire complet et les conventions.

Les annotations qui exigent une constante de compilation (`@RolesAllowed`, `@ConsumeEvent`, `@Claim`, `@CacheResult`) prennent leur valeur depuis ces classes. Toute occurrence d'une string littérale couvrant l'un de ces domaines en dehors des classes de constantes est un bug à corriger.

Règle de décision : **si une chaîne sémantique est référencée par deux classes ou plus, elle est centralisée. Sinon, elle reste inline.**

---

## 4. Sécurité

### 4.1 JWT — accès aux claims

Les claims du JWT sont consommés uniquement par injection MicroProfile JWT :

```java
@Inject
@Claim(standard = Claims.kid)
ClaimValue<Long> userID;
```

- Ne jamais parser le token manuellement.
- Ne jamais lire les claims via un autre mécanisme dans la couche métier.

### 4.2 Authentification par défaut
- Tout endpoint est `@Authenticated` par défaut.
- `@PermitAll` doit être explicite et justifié par un commentaire.
- `@RolesAllowed` sur tout endpoint à accès restreint par rôle.

---

## 5. Architecture en couches

### 5.1 Chaîne d'appel stricte

Le flux est strict : **Controller → Service → Repository**.

- Un contrôleur (resource JAX-RS) ne doit jamais injecter ni appeler un repository directement.
- Toute lecture ou écriture passe par un service.

### 5.2 Persistance via le repository
- Ne jamais appeler une méthode de persistance ou de mise à jour directement sur une entité (`entity.persist()`, `entity.persistAndFlush()`, `entity.update(...)`, etc.).
- Toutes les opérations de base de données passent par le repository. L'entité ne porte aucune logique de persistance.

### 5.3 Frontière transactionnelle
- `@Transactional` se place **uniquement sur la couche service**.
- Jamais sur un repository, jamais sur un contrôleur.
- Le service est la frontière transactionnelle de l'application.

---

## 6. Persistance et entités

### 6.1 Séparation Entity / DTO stricte
- Une entité JPA ne sort **jamais** d'un service.
- L'API expose uniquement des `record` DTO (`...RequestDto`, `...ResponseDto`).
- Pas de `@JsonProperty`, `@JsonIgnore` ou autre annotation Jackson sur une entité.
- Les conversions Entity ↔ DTO se font explicitement dans le service ou via un mapper dédié (MapStruct).

### 6.2 Identifiants
- L'identifiant unique de toute entité est un **`UUID`** stocké directement en `_id` MongoDB. Pas de séparation entre un `id` interne et un `publicId` externe.
- Les UUID sont générés via `IdGenerator` (UUID v7 — time-ordered) injecté par CDI. **Interdit** : appeler `UUID.randomUUID()` directement dans un service ou repository métier.
- Les références inter-entités utilisent directement l'`_id` de l'entité cible. Convention de nommage : `<entité>_id` côté BSON (`supplier_id`, `site_id`, `tenant_id`), `<entité>Id` côté Java (`supplierId`, `siteId`, `tenantId`).
- Aucune URL d'API publique ne contient un ID numérique séquentiel.

### 6.3 Champs d'audit automatiques

Toute entité métier porte :
- `createdAt` (`Instant`)
- `updatedAt` (`Instant`)
- `createdBy` (`UUID` utilisateur)
- `updatedBy` (`UUID` utilisateur)

Ces champs sont renseignés automatiquement via `EntityListener` ou Hibernate Envers. Jamais à la main dans les services.

---

## 7. Multi-tenant

### 7.1 Contexte tenant
- Le `tenantId` n'est **jamais** un paramètre passé manuellement entre couches (contrôleur, service, repository).
- Il vient d'un `TenantContext` CDI `@RequestScoped`, alimenté par un filtre JAX-RS qui lit le JWT.

### 7.2 Filtrage automatique
- Tout repository qui touche une table tenant-scoped applique le filtre tenant automatiquement (filtre Hibernate `@Filter` activé en début de requête, ou intercepteur).
- Aucune requête métier ne doit avoir besoin d'ajouter `where tenant_id = ?` à la main.

---

## 8. Validation et gestion d'erreurs

### 8.1 Validation Bean Validation
- `@Valid` sur tout `@RequestBody` d'un contrôleur.
- Contraintes (`@NotBlank`, `@Email`, `@Size`, `@Min`, etc.) déclarées sur les DTO de requête.
- Pas de validation manuelle dans les services pour ce que Bean Validation sait faire.

### 8.2 Exceptions métier

Hiérarchie d'exceptions custom à utiliser :
- `BusinessException` — règle métier violée
- `NotFoundException` — ressource introuvable
- `UnauthorizedException` — accès refusé
- `ConflictException` — conflit de données (duplicat, etc.)

### 8.3 Gestion centralisée
- Un `ExceptionMapper` JAX-RS par famille d'exception, qui produit la réponse `ApiResponse<T>` standard.
- **Interdit** dans les contrôleurs : `try/catch` qui renvoient un `Response` à la main.

---

## 9. Opérations asynchrones

Toute opération asynchrone — envoi d'e-mail, SMS, notification, webhook sortant, traitement différé — doit utiliser le pattern **event bus** de Quarkus :

- Émission : `EventBus#publish(...)` ou `EventBus#send(...)`.
- Consommation : méthode annotée `@ConsumeEvent`.

Pas de `CompletableFuture` posé ad hoc, pas d'appel synchrone bloquant pour ces usages.

---

## 10. Contrat d'API

### 10.1 Versionning
- Préfixe d'URL `/api/v1/...` systématique dès le premier endpoint.
- Toute évolution cassante = nouvelle version (`/api/v2`).

### 10.2 Enveloppe de réponse standard

**Toute** API, sans exception, renvoie un `ApiResponse<T>` :

```java
public record ApiResponse<T>(
    Integer statusCode,
    String statusMessage,
    T data
) implements Serializable {}
```

### 10.3 Réponses paginées

Pour toute réponse de type liste, le type de retour complet est **`ApiResponse<Pagination<T>>`**. `Pagination<T>` ne remplace **jamais** l'enveloppe `ApiResponse` — il s'imbrique dedans, comme `data`.

```java
public record Pagination<T>(
    Long total,
    int totalOfPages,
    Long perPage,
    String[] sorts,
    String order,
    int currentPage,
    int nextPage,
    int previousPage,
    Map<String, String> filters,
    List<T> items
) implements Serializable {}
```

Exemple concret côté contrôleur :

```java
// GET /api/v1/purchases
public Response listPurchases(/* params */) {
    Pagination<PurchaseResponseDto> page = purchaseService.list(/* ... */);
    ApiResponse<Pagination<PurchaseResponseDto>> body =
        new ApiResponse<>(200, "OK", page);
    return Response.ok(body).build();
}

// GET /api/v1/purchases/{id}
public Response getPurchase(@PathParam("id") UUID id) {
    PurchaseResponseDto purchase = purchaseService.findById(id);
    ApiResponse<PurchaseResponseDto> body =
        new ApiResponse<>(200, "OK", purchase);
    return Response.ok(body).build();
}
```

Règles dérivées :
- Nommage strictement camelCase à l'intérieur de `Pagination`, jamais snake_case.
- Une réponse "single item" est `ApiResponse<XxxResponseDto>` ; une réponse "list" est `ApiResponse<Pagination<XxxResponseDto>>`. Il n'existe pas d'autre forme.
- **Interdit** : `Pagination<T>` renvoyé seul, `List<T>` brut, objet ad hoc, ou tout autre wrapper.

### 10.4 Bornes de pagination
- `perPage` par défaut = `20`.
- `perPage` maximum = `100`. Toute valeur supérieure est rejetée (`400`).
- Pas de "give me all" sur une API publique.

### 10.5 Codes HTTP corrects
- `201 Created` + header `Location` pour les créations.
- `204 No Content` pour les `DELETE` et les `PUT` sans corps de réponse.
- `400 Bad Request` pour les erreurs de syntaxe / désérialisation.
- `422 Unprocessable Entity` pour les erreurs de validation métier.
- `404 Not Found` pour les ressources introuvables.
- Les contrôleurs renvoient des `Response`, pas directement un DTO.

---

## 11. Migrations de base de données

- Ne jamais modifier une migration déjà exécutée.
- Pour corriger l'état du schéma, créer une **nouvelle** migration corrective.
- Exception : sur demande explicite. En cas de doute, demander confirmation avant toute modification.

---

## 12. Templates de mails

- Tous les mails utilisent des templates **Qute HTML**.
- Chaque template hérite d'un layout HTML commun (`{#include layout/base.html}` ou équivalent).
- Aucun template de mail autonome, sans héritage du layout.

---

## 13. Observabilité

### 13.1 Logging
- JBoss Logger (`@Inject Logger log;`) ou SLF4J. **Interdit** : `System.out`, `System.err`, `e.printStackTrace()`.
- Niveaux respectés : `DEBUG` (dev / diagnostic), `INFO` (événements métier), `WARN` (récupérable, à surveiller), `ERROR` (nécessite alerte).
- **Jamais** logger : mot de passe, token JWT en clair, numéro de carte, PII brute (CNI, téléphone non masqué, etc.).

### 13.2 Health checks
- `SmallRye Health` activé. Un check `@Liveness` et un `@Readiness` par dépendance externe critique (base de données, broker de messages, services tiers).

### 13.3 Métriques
- Micrometer pour les métriques métier (compteurs de transactions, latences des endpoints critiques, etc.).
- Les métriques techniques de base (JVM, HTTP) sont activées par défaut via les extensions Quarkus.

---

## 14. Tests

### 14.1 Pyramide
- Tests unitaires : services, mappers, logique métier pure. Mock des dépendances.
- Tests d'intégration : contrôleurs end-to-end via `RestAssured`, repositories contre une vraie base.

### 14.2 Base de test
- **Interdit** : H2 ou autre base en mémoire pour les tests d'intégration.
- Obligatoire : **Testcontainers PostgreSQL** (ou Quarkus DevServices quand applicable).

### 14.3 Couverture
- Tout service public exposé doit avoir au moins un test d'intégration.
- Toute règle métier non triviale a son test unitaire.

---

## 15. Documentation OpenAPI

Toute classe et tout endpoint exposés doivent être documentés :

- Contrôleurs : `@Tag` au niveau de la classe.
- Endpoints : `@Operation`, `@APIResponse` (au minimum pour les codes 200 / 201, 400, 401 / 403, 404, 422, 500 selon le cas).
- Request bodies et DTO : `@Schema` sur la classe et sur les champs sensibles (description, exemple, contraintes).

Aucun endpoint ne doit être livré sans annotation OpenAPI complète.

---

## Récapitulatif des interdictions

| ❌ Interdit | ✅ Attendu |
|---|---|
| Maven | Gradle |
| `.properties` | `.yml` / `.yaml` |
| `application.yml` unique avec `%dev`, `%qa`, `%prod` | Un fichier par environnement |
| Secrets en dur ou versionnés | Variables d'environnement / Vault |
| CORS `*` en production | Liste blanche stricte par environnement |
| Inner classes | Une classe = un fichier |
| Lombok | `record` ou code explicite |
| `snake_case` | `camelCase` partout |
| Packages par couche | Packages par feature |
| `java.util.Date`, `Calendar` | `java.time.*` |
| String littérale partagée (rôle, événement, claim, type d'audit, nom de cache, base contrôle) | Constante depuis `Roles`, `Events`, `JwtClaims`, `AuditEventType`, `CacheNames`, `ControlPlane` |
| `@ConfigProperty` dans une classe métier | Interface `@ConfigMapping` injectée |
| Parsing JWT manuel | `@Inject @Claim` |
| Endpoint sans authentification explicite | `@Authenticated` par défaut |
| `entity.persist()` dans un service | Appel via repository |
| Entité retournée par un service ou exposée par l'API | DTO `record` (Request / Response) |
| `@Transactional` sur repository ou contrôleur | `@Transactional` sur service uniquement |
| ID numérique séquentiel exposé dans une URL d'API | `UUID` v7 en `_id`, exposé tel quel |
| `UUID.randomUUID()` dans un service ou repository | `IdGenerator.newId()` injecté |
| Pattern `id ObjectId` + `publicId UUID` séparés | Un seul `UUID` en `_id`, pas de duplication |
| Champs d'audit renseignés à la main | `EntityListener` / Envers |
| `tenantId` passé en paramètre entre couches | `TenantContext` CDI request-scoped |
| `where tenant_id = ?` à la main | Filtre Hibernate automatique |
| Validation manuelle dans le service | Bean Validation sur DTO |
| `try / catch` renvoyant une `Response` dans un contrôleur | `ExceptionMapper` global |
| Repository injecté dans un contrôleur | Controller → Service → Repository |
| `CompletableFuture` pour mail / SMS | Event bus (`@ConsumeEvent`) |
| API sans préfixe `/api/v1` | Versionning systématique |
| `List<T>` brut renvoyé par une API | `ApiResponse<Pagination<T>>` |
| `perPage` sans borne max | Défaut 20, max 100 |
| `200 OK` partout | Codes HTTP corrects (201, 204, 422...) |
| Modifier une migration appliquée | Nouvelle migration corrective |
| Template de mail sans layout | Héritage Qute du layout commun |
| `System.out.println`, `printStackTrace` | JBoss Logger / SLF4J |
| Logs avec secret, token, PII brute | Logs masqués / filtrés |
| H2 pour les tests d'intégration | Testcontainers PostgreSQL |
| Endpoint sans `@Operation` / `@Tag` | Documentation OpenAPI complète |
