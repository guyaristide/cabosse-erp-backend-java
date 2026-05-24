# Roadmap de livraison backend — Cabosse ERP

**Référence** : NEIBA-DELIV-2026-001 · Mai 2026
**Statut** : plan de découpage actif, mis à jour à chaque clôture de phase.

Ce document organise la livraison du backend Quarkus en phases successives, depuis l'état actuel (starter quasi vide, build.gradle hérité d'un mélange Postgres/Mongo) jusqu'à l'admin plateforme M9 entièrement câblée côté frontend. Chaque phase a un livrable mesurable et débloque la suivante.

Référence implicite à toutes les phases : `CLAUDE.md`, `multi-tenant-architecture.md` (NEIBA-ARCH-2026-001), `shared-constants.md` (NEIBA-ARCH-2026-002).

---

## État de départ (2026-05-17)

- `build.gradle` Groovy DSL avec un mélange incohérent : `quarkus-mongodb-panache` **et** `quarkus-liquibase` + `quarkus-liquibase-mongodb`. Hérité de l'ancien memo NEIBA-TECH-2026-001 et jamais nettoyé après le pivot MongoDB acté dans NEIBA-ARCH-2026-001.
- `application.yml` contient le placeholder `greeting.message: "hello"` du starter Quarkus.
- 3 fichiers d'exemple Java sous `com.ntech.cabosse.*` (`ExampleResource`, `GreetingConfig`, `SomePage`) — à supprimer.
- Aucune entité, aucun service, aucun repository, aucune migration Mongock.
- Aucun test au-delà de l'exemple `ExampleResourceTest`.
- Le `CLAUDE.md` à la racine du repo backend cite encore Postgres/Hibernate/RLS — superseded par `docs/*`.

---

## Phase A — Foundation

**Objectif** : socle technique opérationnel, sans aucune feature métier, mais sur lequel toutes les features suivantes pourront s'appuyer sans ré-architecturer quoi que ce soit.

**Livrables :**

1. **Build & config**
   - `build.gradle` reste en **Groovy DSL** (décision projet — pas de migration Kotlin DSL).
   - Dépendances aligned MongoDB pivot : `quarkus-mongodb-client`, `quarkus-mongodb-panache` (control plane uniquement), `quarkus-smallrye-jwt` (+ build), `quarkus-rest-jackson`, `quarkus-cache`, `quarkus-scheduler`, `quarkus-hibernate-validator`, `quarkus-smallrye-openapi`, `quarkus-mailer` + `quarkus-qute`, Mongock standalone + driver sync v4, uuid-creator (v7), Testcontainers MongoDB. Retrait de `quarkus-liquibase` et `quarkus-liquibase-mongodb`.
   - `application.yml` (commun) + `application-dev.yml`, `application-qa.yml`, `application-prod.yml`
   - `gradle.properties` correctement renseigné (versions Quarkus, plugin)
2. **Arborescence packages par feature** (cf. `CLAUDE.md` §3.2)
3. **Constantes partagées** (cf. `shared-constants.md`)
   - `com.ntech.cabosse.shared.security.Roles`
   - `com.ntech.cabosse.shared.security.JwtClaims`
   - `com.ntech.cabosse.shared.events.Events`
   - `com.ntech.cabosse.shared.audit.AuditEventType` (enum)
   - `com.ntech.cabosse.shared.persistence.ControlPlane`
   - `com.ntech.cabosse.shared.persistence.CacheNames`
   - `ConstantsConsistencyCheck` au démarrage
4. **Infrastructure transversale**
   - `com.ntech.cabosse.shared.api.ApiResponse<T>` (record)
   - `com.ntech.cabosse.shared.api.Pagination<T>` (record)
   - `com.ntech.cabosse.shared.persistence.IdGenerator` (UUID v7 via uuid-creator)
   - `com.ntech.cabosse.shared.persistence.MongoCodecConfiguration` (UUID ↔ String codec)
   - `com.ntech.cabosse.shared.config.ApplicationConfig` (`@ConfigMapping`)
5. **Contexte tenant** (cf. `multi-tenant-architecture.md` §6)
   - `com.ntech.cabosse.shared.tenant.TenantContext` (`@RequestScoped`)
   - `TenantContextFilter` (hydrate depuis JWT)
   - `TenantStatusGuard` + `TenantRegistryCache` (`@CacheResult` sur `CacheNames.TENANT_REGISTRY`)
   - `TenantAwareExecutor.runForTenant(...)` (jobs / event consumers hors HTTP)
6. **Providers de données** (cf. `multi-tenant-architecture.md` §7)
   - `ControlPlaneProvider`
   - `TenantMongoDatabaseProvider`
7. **Exceptions & ExceptionMappers**
   - Hiérarchie : `BusinessException`, `NotFoundException`, `UnauthorizedException`, `ConflictException`, `ValidationException`
   - `ExceptionMapper`s qui produisent `ApiResponse<T>` avec le bon code HTTP
8. **Mongock** : `TenantMigrationRunner` + `StartupMigrationRunner` (cf. `multi-tenant-architecture.md` §10) + 1ʳᵉ migration générique au catalogue (placeholder index) pour valider la chaîne.
9. **Nettoyage**
   - Suppression de `ExampleResource`, `GreetingConfig`, `SomePage`, et leurs tests.
10. **Test fumée** : 1 endpoint `GET /api/v1/health/ping` non protégé qui retourne `ApiResponse<String>("OK")`, avec test d'intégration Testcontainers MongoDB qui prouve que le replica set démarre correctement.

**Critère de sortie** : `./gradlew quarkusDev` démarre, l'application se connecte à un MongoDB local, `GET /api/v1/health/ping` répond `200 {"statusCode":200,"statusMessage":"OK","data":"OK"}`.

**Non livré (volontairement) en Phase A** : aucune entité métier, aucun service métier, aucun endpoint M9 — c'est Phase B+.

---

## Phase B — Authentification + Tenants M9

**Objectif** : connexion utilisateur fonctionnelle, annuaire des tenants en lecture, provisioning end-to-end d'un nouveau tenant.

**Livrables :**

1. **Auth** (cf. `multi-tenant-architecture.md` §5)
   - `com.ntech.cabosse.auth.AuthResource` : `POST /api/v1/auth/login`
   - `AuthService` (recherche user, vérification hash, contrôle statut tenant)
   - `JwtIssuer` (claims `tenantId`, `tenantDatabaseName`, `groups`)
   - `PasswordHasher` (BCrypt ou Argon2)
   - `RefreshTokenService` (rotation des refresh tokens)
2. **Tenants** (cf. `multi-tenant-architecture.md` §4.2, §9, §12)
   - Entités control plane : `TenantEntity`, `UserEntity`, `PlanEntity`
   - Repositories Panache (control plane uniquement)
   - `com.ntech.cabosse.tenant.TenantRegistryService` (list paginée, detail)
   - `com.ntech.cabosse.tenant.TenantProvisioningService` (10 étapes : enregistrement, création DB physique, migrations Mongock, seed référentiels, création admin INVITED, activation, audit, événement)
   - `TenantsResource` : `GET /api/v1/admin/tenants`, `GET /api/v1/admin/tenants/{id}`, `POST /api/v1/admin/tenants`
   - DTOs Request/Response + Mappers MapStruct
3. **Invitation admin tenant**
   - `TenantInvitationListener` (`@ConsumeEvent(Events.TENANT_PROVISIONED)`)
   - Template Qute `mail/tenant-invitation.html` (hérite d'un layout `mail/layout/base.html`)
   - `MailService` + `InvitationTokenService`
4. **Tests d'intégration Testcontainers**
   - `MongoReplicaSetTestResource` (réutilisable Phase C+)
   - `AuthResourceIT`
   - `TenantProvisioningIT` (vérifie : entrée control plane, base physique, collections seedées, admin INVITED, événement publié)

**Critère de sortie** : un agent peut s'authentifier avec un compte seed, un super-admin peut créer un tenant complet en une requête, l'admin tenant reçoit un mail d'invitation (capturé en test via mock SMTP).

---

## Phase C — Support tickets + Audit M9

**Objectif** : la file des tickets support et le journal d'audit sont opérationnels côté API.

**Livrables :**

1. **Support tickets**
   - `SupportTicketEntity`, `TicketMessageEntity` (sous-document ou collection liée)
   - `SupportTicketRepository` (control plane Panache)
   - `SupportTicketService` :
     - listing paginé + filtres priorité / statut / catégorie
     - `getById`, `assign`, `setPriority`, `setStatus` (avec validation des transitions cf. graphe frontend `TICKET_STATUS_TRANSITIONS`), `reply` (avec `isInternal` + auto-progression statut)
   - `SupportTicketsResource` : `GET`, `GET /{id}`, `POST /{id}/assignee`, `POST /{id}/priority`, `POST /{id}/status`, `POST /{id}/messages`
2. **Audit**
   - `GlobalAuditEntry` (déjà décrit §4.2 du doc multi-tenant)
   - `AuditLogger` (méthode `log(GlobalAuditEntry)` + factories `tenantProvisioned`, `ticketAssigned`, etc.)
   - `AuditResource` : `GET /api/v1/admin/audit` paginé avec filtres catégorie / tenant / acteur / plage de dates
   - Interceptor `@AuditAction` pour automatiser les écritures à partir des annotations sur les méthodes de service (optionnel — sinon appels explicites)
3. **Tests d'intégration**
   - `SupportTicketResourceIT` (CRUD + actions + transitions de statut rejetées avec 422)
   - `AuditResourceIT`

**Critère de sortie** : la file des tickets et l'historique d'audit peuvent être consommés depuis n'importe quel client HTTP authentifié `PLATFORM_ADMIN`.

---

## Phase D — Impersonation + Technique M9

**Objectif** : les outils d'opération avancés (impersonation, statut technique d'un tenant, déclenchement de migrations / backups manuels) sont disponibles.

**Livrables :**

1. **Impersonation** (cf. `multi-tenant-architecture.md` §12.3)
   - `ImpersonationService` : émet un JWT enrichi du claim `impersonatedBy`, durée bornée (15 / 30 / 60 / 240 minutes), audit `IMPERSONATION_STARTED`
   - `ImpersonationResource` : `POST /api/v1/admin/impersonate`, `DELETE /api/v1/admin/impersonate/{sessionId}` (clôture explicite avec `IMPERSONATION_ENDED`)
2. **Cross-tenant query** (cf. `multi-tenant-architecture.md` §12.2)
   - `CrossTenantQueryService` (réservé contrôleurs M9, audit `CROSS_TENANT_ACCESS` systématique)
3. **Statut technique d'un tenant**
   - `TenantTechnicalService` qui consolide :
     - `db.stats()` → taille DB, nombre de collections
     - `db.<col>.stats()` → docs, taille, indexes, lastWrite pour chaque collection métier
     - lecture `mongockChangeLog` → historique des migrations avec statut, durée, erreur
     - lecture `cabosse_control.tenant_backups` → 5 derniers backups
   - `TenantTechnicalResource` : `GET /api/v1/admin/tenants/{id}/technical`
4. **Migrations manuelles** (cf. `multi-tenant-architecture.md` §10.4)
   - `POST /api/v1/admin/tenants/{id}/migrations` : déclenche `TenantMigrationRunner.runMigrationsFor(...)`, audité
5. **Backups manuels** (cf. `multi-tenant-architecture.md` §13)
   - `BackupRequestService` : publie un événement `tenant.backup.requested` sur l'event bus → worker shell qui appelle `mongodump --db <tenant>` (le worker est out of scope Java, mais le service applicatif fait la queue)
   - `TenantBackupResource` : `POST /api/v1/admin/tenants/{id}/backups`
6. **Suspension / suppression**
   - `POST /api/v1/admin/tenants/{id}/suspend` (statut → SUSPENDED, audit, événement)
   - `DELETE /api/v1/admin/tenants/{id}` (archivage + `dropDatabase()`, audit `TENANT_DELETED`, événement)
7. **Tests d'intégration**
   - `ImpersonationResourceIT`
   - `TenantTechnicalResourceIT` (vérifie l'agrégation des stats sur un tenant freshly provisioned)
   - `MigrationRerunIT`
   - `TenantDeletionIT`

**Critère de sortie** : un super-admin a tous les outils opérationnels pour diagnostiquer et agir sur un tenant en production.

---

## Phase E — Frontend → backend

**Objectif** : la PWA frontend consomme les API réelles à la place des mocks, sans refacto métier.

**Livrables :**

1. **HTTP client typé**
   - `src/lib/api/client.ts` : `apiClient.get/post/put/delete<T>()` qui :
     - préfixe `import.meta.env.VITE_API_BASE_URL` + `/api/v1/...`
     - attache `Authorization: Bearer <jwt>` depuis l'auth store
     - parse `ApiResponse<T>` (extraction automatique du `data`)
     - lance `ApiError(statusCode, statusMessage)` sur 4xx/5xx
     - retry exponential backoff sur 5xx (max 3 tentatives)
2. **Refacto méthode par méthode** de `apiFacade.*`
   - Chaque méthode bascule de `okPaginated(MOCK_ARRAY, ...)` vers `apiClient.get(...)` — un module à la fois (backoffice d'abord, puis référentiels, puis modules opérationnels)
   - Pendant la transition, un flag `VITE_USE_MOCKS=true` route encore vers les mocks pour les modules non migrés
3. **Alignement URLs**
   - Tous les commentaires JSDoc dans `apiFacade.*` qui disaient `/api/me/...` corrigés en `/api/v1/...`
4. **Auth flow**
   - `useAuthStore.login({email,password})` appelle `POST /api/v1/auth/login`
   - Stockage du JWT en `httpOnly cookie` ou `sessionStorage` (à arbitrer selon la sensibilité)
   - Décodage des claims `tenantId`, `groups` → hydratation du store sans appel séparé
   - Refresh token rotation
5. **Suppression progressive des mocks**
   - Une fois tous les façades migrés et stables, les fichiers `*/api/mocks.ts` deviennent juste des seeds pour Testcontainers backend — plus consommés par le frontend
6. **Tests E2E Playwright** (à confirmer en début de phase)
   - Login → navigation tenants → ouverture fiche → onglet technique → relancer migrations → vérifier l'audit log

**Critère de sortie** : la PWA déployée sur `cabosse.poc-demo.com` consomme l'API Quarkus déployée à côté, sans dégradation fonctionnelle vs le mode mock actuel.

---

## Sujets à arbitrer (hors roadmap)

Identifiés pendant la rédaction, ils n'appartiennent à aucune phase mais bloqueront tôt ou tard. À traiter en mémo séparé.

1. ~~**DTO de provisioning**~~ — **Résolu Phase B (Option 1)** : le DTO backend a été étendu pour absorber les ~25 champs du frontend. Voir `multi-tenant-architecture.md` §9.2 mis à jour.
2. ~~**Double statut tenant**~~ — **Résolu Phase B** : `TenantEntity` porte désormais `status` (technique, enum `TenantStatus`) **et** `commercialStatus` (commercial, enum `CommercialStatus` : `TRIAL | PILOT | PRODUCTION`). Les deux sont éditables indépendamment.
3. **Référentiel Villes tenant-scoped** — implémenté côté frontend (`findOrCreate` auto-stitching pour éviter les doublons d'adresse). À porter côté backend (collection `cities` tenant-scoped + endpoints CRUD + `findOrCreate` + `merge`) si on veut que le pattern survive.
4. **Rémanence Postgres dans `CLAUDE.md` racine du backend** — fichier non aligned avec le pivot Mongo. Décision utilisateur : ne PAS le toucher dans le cadre de cette roadmap (le canon est `docs/*`, le `CLAUDE.md` racine est legacy).
5. **Tests d'intégration §14.2** — `docs/CLAUDE.md` mentionne "Testcontainers PostgreSQL" en §14.2, ce qui est rémanent. La règle effective est §14.3 du doc multi-tenant : Testcontainers MongoDB en mode replica set.

---

## Suivi de progression

| Phase | Statut | Démarré | Terminé | Notes |
|---|---|---|---|---|
| A — Foundation | ✅ Code livré | 2026-05-17 | 2026-05-17 | 33 classes Java + 4 yml + clés dev RSA. `./gradlew compileJava` + `compileTestJava` OK. Test d'intégration `HealthResourceTest` à exécuter au prochain run avec Docker actif. |
| B — Auth + Tenants M9 | ✅ Code livré | 2026-05-17 | 2026-05-17 | DTO étendu (Option 1 actée), `commercialStatus` ajouté, multipart pour create, endpoints logo dédiés (GET/PUT/DELETE), provisioning service 10 étapes, invitation listener + template Qute. **Refactor mid-phase** : abandon de `TenantLogoEntity` au profit de `CloudFileEntity` générique (cf. `file-storage.md` NEIBA-ARCH-2026-003) — règle "aucun binaire dans une entité métier". Compile OK. Tests d'intégration à étoffer en début Phase C. |
| C — Support + Audit M9 | En attente | — | — | |
| D — Impersonation + Technique M9 | En attente Phase C | — | — | |
| E — Frontend → backend | En attente Phase D | — | — | |

Mettre à jour ce tableau à chaque clôture de phase, et fermer les sujets à arbitrer au fur et à mesure qu'ils sont tranchés (déplacer vers un mémo dédié ou rayer s'ils sont absorbés dans une phase).

---

**Fin du document.**

*NEIBA Technologies · Cabosse ERP · Mai 2026 · v1.0*
