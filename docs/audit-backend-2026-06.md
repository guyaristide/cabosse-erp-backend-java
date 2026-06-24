# Audit backend — red flags & violations de best practices

> **Date** : 2026-06-24
> **Méthode** : 2ᵉ passe d'audit (4 revues parallèles : logique métier, code récent, validation des entrées, cohérence/maintenabilité). Suit une 1ʳᵉ passe dont les correctifs (auth Campaign, mapper catch-all, vatRecoverable, index tenant_backups, lock optimiste sur Ventes/Achats/Production/Réceptions, plafond `ListCap`) sont **déjà appliqués**.
> **Statut** : ⚠️ **DOCUMENT D'ANALYSE — aucun correctif appliqué.** Certaines validations sont volontairement absentes ; chaque point doit être tranché manuellement avant action.

## Comment lire ce document

Chaque constat porte : un **id**, une **sévérité**, le **fichier:ligne**, le **scénario qui casse** (pas de supposition non démontrée), et un **correctif proposé**. La colonne **Décision** est vide — à remplir : `À CORRIGER` / `VOLONTAIRE` (validation laissée exprès) / `À DISCUTER` / `REJETÉ`.

### ⚠️ Réserve d'honnêteté
Les constats **🔴 #1, #2, #3** (corruption CMUP) proviennent d'une lecture du code avec un scénario plausible mais **n'ont pas été re-vérifiés ligne par ligne** par moi. Ce sont des touches sensibles à la valorisation : à confirmer avant tout correctif.

### Points vérifiés **sains** (aucune action)
Lock optimiste des 4 repos (incrément/filtre/init/restauration sur conflit) ; `ListCap` ; `TenantSubscriptionService` (switch exhaustif, bornes `@Min(1)`) ; migrations M024–M028 idempotentes ; `CATALOG` ↔ 28 migrations synchronisé (test de cohérence à jour) ; tous les body POST/PUT portent `@Valid` ; enums parsés en try/catch (filtre ignoré, jamais de 500) ; regex Mongo via `Pattern.quote` (pas d'injection regex) ; upload logo (taille + MIME via `FileUploadLimits`).

---

## 🔴 Critiques — corruption de données / blocage fonctionnel dur

| id | Fichier:ligne | Problème | Scénario qui casse | Correctif proposé | Décision |
|----|---------------|----------|--------------------|-------------------|----------|
| C1 | `stock/service/StockService.java` ~341-377, ~507 | **Contre-passation OF/transfert repondère le CMUP** au lieu de le restaurer. `applyEntryAtomic` recalcule une moyenne pondérée même sur un `IN` compensatoire | Article X CMUP=1000 au start, on consomme (stock 100→0). Achat réappro à 1400 → CMUP=1400. Annulation OF : `IN` compensatoire de 100 à 1000 → CMUP recalculé `(100·1400+100·1000)/200 = 1200`. Devrait rester 1400. Idem compensation transfert (réinjecte en `IN` au lieu d'un `TRANSFER_IN` neutre) | `MovementKind` dédié « reversal » exclu de la moyenne, ou recalcul analytique du CMUP cible (restaurer `oldCmup` sans repondérer) | |
| C2 | `stock/service/StockService.java:283-293` | **`IN`/`OPENING`/`TRANSFER_IN` sur `oldQty` négatif → CMUP aberrant** (signe inversé / division ~0). La garde `$lte newQty 0 → 0` ne couvre pas `oldQty<0` laissant un `newQty>0` | `oldQty=-50`, `oldCmup=1000`, entrée `inQty=60` à `inPu=1200` → `newQty=10`, CMUP=`(-50·1000+60·1200)/10 = 2200` (article acheté 1200). Le stock négatif vient d'une contre-passation `force=true` qui descend sous zéro (cas prévu l.155-157) | Si `oldQty ≤ 0` : ignorer l'ancien terme, `cmup = inPu` (repartir du prix d'entrée) | |
| C3 | `production/service/ManufacturingOrderService.java:294-295` | **CMUP PF = `totalMaterialCostFcfa / producedQty`, `producedQty` non borné par rapport au planifié** | OF 1000 u, coût 1 000 000. Complétion `producedQty=1` (faute) → CMUP PF=1 000 000/u. À l'inverse `producedQty=100000` → CMUP=10, stock gonflé | Encadrer `producedQty` vs `plannedQty` (tolérance ±x %), ou alerter si écart important | |
| C4 | accounting (génération pièce BC TVA récupérable) | **« Pièce déséquilibrée » sur écart d'arrondi → rollback de la livraison** | BC à TVA récupérable : la pièce comptable auto-générée peut être déséquilibrée d'1 FCFA d'arrondi → exception → `markDelivered` rollback. Blocage fonctionnel dur | Absorber l'écart d'arrondi sur une ligne d'ajustement, ou tolérance ±1 FCFA à l'équilibrage | |
| C5 | `reception/service/DirectReceiptService.java` | **Statut `PAID` calculé sur le NOMBRE de lignes payées, pas les montants** ; `recordPayment` sans plafond/signe | RD multi-lignes : un acompte sur chaque ligne marque la RD `PAID` alors que la dette subsiste. Surpaiement possible → décaissement négatif | Calculer le statut sur `totalPaid` vs `totalDû` ; borner le paiement (≤ reste dû, signe positif) | |
| C6 | agriculture/processing (QC fèves) | **Re-validation QC repointe `dryingBatchId` en update → double entrée stock fèves** ; `acceptedKg` non borné au poids séché ; `weightLossPct` négative possible | Re-valider un `BeanQualityCheck` génère un 2ᵉ mouvement `IN` (lotRef `LOT-FEVE`). `acceptedKg > poids séché` accepté | Idempotence du mouvement QC (1 QC = 1 IN max) ; borner `acceptedKg ≤ poids séché` ; garder `weightLossPct ≥ 0` | |
| C7 | `sale/dto/SaleUpsertDto.java:36,38` ; `sale/dto/SaleLineDto.java:26` | **`discountPct` / `vatRatePct` non bornés → facture à total négatif ou gonflé forgeable** | `{"discountPct":999}` → remise 999 %, `totalTtcFcfa` négatif persisté ; `{"vatRatePct":-20}` → TVA négative. **Asymétrie** : `PurchaseOrderLineDto:31-33` et `DirectReceiptUpsertDto` bornent déjà [0,100] | `@DecimalMin("0") @DecimalMax("100")` sur les 3 champs (aligner sur Achats) | |

---

## 🟠 Importants

### Validation des entrées (souvent volontairement absente — à confirmer)

| id | Fichier:ligne | Problème | Input qui casse | Correctif proposé | Décision |
|----|---------------|----------|-----------------|-------------------|----------|
| H1 | `accounting/controller/AccountingResource.java:172,196` | `UUID.fromString(bankAccountIdRaw)` non gardé → 500 | `?bankAccountId=abc` | Helper `parseUuid` → `BusinessException` 400 (le contrôleur a déjà `parseEnum`/`parseDate`) | |
| H2 | `accounting/.../AccountingResource.java:276` → `AccountingQueryService.java:277` | `YearMonth.parse(yearMonth)` non gardé → 500 | `POST .../tva/2026-13/mark-ready` | Valider `yearMonth` (regex `\d{4}-\d{2}` ou try/catch) en tête. Vérifier aussi `markDeposed:293` | |
| H3 | `agriculture/parcel/dto/ParcelUpsertDto.java:25-27` | **Coordonnées GeoJSON jamais validées** ; `gpsCenter` `@NotNull` mais ni taille ni bornes | `gpsCenter=[]` / `[200,100]` / lat-lng inversé / ring 2 pts / non fermé → 500 index `2dsphere` ou corruption géo-traçabilité EUDR | Valider dans `ParcelService` : `gpsCenter.size()==2`, `-180≤lng≤180`, `-90≤lat≤90` ; ring ≥4 pts, fermé, dans les bornes | |
| H4 | global + imports | **Aucun `quarkus.http.limits.max-body-size`** ; listes d'import (`ArticleResource`, `SupplierResource`, `CustomerResource`, `SiteResource`, `ExpenseTypeResource`, `DirectReceiptImportRequestDto.rows`, `BankStatementImportService`) sans `@Size(max)` ni `@Valid` → OOM/DoS ; `commit()` rappelle `preview()` (double coût) | Gros CSV/Excel ou JSON volumineux | `max-body-size` explicite en yml + `@Size(max=5000)` par liste d'import | |
| H5 | `accounting/service/BankStatementImportService.java:161-164` | **Auto-détection séparateur ligne par ligne → montant silencieusement faux** | Séparateur `,` + montants `1,5` → colonnes décalées, ligne non comptée dans `skipped` | Détecter le séparateur sur l'**en-tête** seulement ; retourner les lignes rejetées numérotées | |
| H6 | `processing/fermentation/FermentationBatchResource.java:107` ; `drying/DryingBatchResource.java:73` ; `eudr/controller/EudrResource.java:148-176` | Records body **sans `@Valid`** / sans contraintes : `weightOutKg`<0, humidité >100 %, `weightOutKg>weightInKg` → `weightLossPct`<0 ; alerte EUDR `parcelId`/`detectedAt`/`severity` nullables → NPE ; `TransitionAlertPayload` sans `@Valid` | Bodies négatifs / nuls | Annoter records (`@NotNull`, `@DecimalMin("0")`, `@DecimalMax("100")`, `@PastOrPresent`) + `@Valid` | |
| H7 | `members/dto/MemberUpsertDto.java:18,23,26` | `partsSocialesAmount` sans `@DecimalMin("0")` ; `phone`/`mobileMoneyNumber` sans `@Pattern` téléphone (présent ailleurs : `SiteUpsertDto`, `UpdateMePayloadDto`) | Parts sociales négatives | Ajouter bornes + pattern | |
| H8 | `campaign/dto/CampaignUpsertDto.java:39-57` | Bornes (`basePricePerKgFcfa`, `ristournePct`, `premiumPerKg`) **uniquement dans le service** → invisibles dans l'OpenAPI/client TS ; `premiumPerKg`<0 non validé nulle part | — | Porter `@DecimalMin("0")` (+`@DecimalMax("100")` ristourne) dans le DTO | |
| H9 | `sale/dto/SaleUpsertDto`, `achats/dto/PurchaseOrderUpsertDto` | **Dates incohérentes non validées** (`dueDate`/`deliveryDate` < date pièce) → créance « en retard » faussée | `dueDate` antérieure à `saleDate` | Validation cross-field (`@AssertTrue` ou contrôle service) | |
| H10 | `production/.../ManufacturingOrderImportService.java:147` ; `achats/.../PurchaseOrderImportService.java:189` | Types d'article d'import trop permissifs (`TRANSPORT`/`FINISHED_PRODUCT` acceptés comme conso d'OF ; `FINISHED_PRODUCT` en ligne d'achat) | — | `@Pattern` restreignant le sous-ensemble autorisé | |

### Métier / logique

| id | Fichier:ligne | Problème | Correctif proposé | Décision |
|----|---------------|----------|-------------------|----------|
| H11 | ventes (CMUP) | **CMUP vente snapshoté à la création du devis**, jamais rafraîchi à validation/livraison → marge & sortie stock périmées | Re-snapshot du CMUP au moment de la livraison effective | |
| H12 | `stock/service/StockService.java:639-663` (`snapshotAt`) | Reconstruction CMUP à date plafonnée à 1000 mvts → sur couple (article,site) >1000 mvts, CMUP historique = 0 **silencieusement** | Pagination interne ou agrégat serveur | |
| H13 | accounting (RD TVA déductible) | TVA déductible RD sur-évaluée par cumul de TTC arrondis ligne par ligne | Arrondir la TVA au niveau pièce, pas par ligne | |
| H14 | accounting (rapprochement) | `RECONCILED`/`matchedCount` confondent MATCHED/IGNORED et ignorent DISPUTE ; auto-match peut double-rapprocher une pièce sur 2 lignes | Distinguer les statuts ; verrou anti-double-match | |
| H15 | campagne | Campagne `OPEN` unique non garantie ; rattachement récolte↔campagne non vérifié (année libre, hors fenêtre) | Contrainte unicité OPEN + validation fenêtre | |
| H16 | EUDR | Transitions (`markAccepted/Rejected`, `transitionStatus`) sans garde d'état ; DDR agrège **toutes** les parcelles COMPLIANT du tenant (origine fausse) | Garde de transition + filtrage DDR sur la vente concernée | |

### Code récent (mon lot — à durcir)

| id | Fichier:ligne | Problème | Correctif proposé | Décision |
|----|---------------|----------|-------------------|----------|
| H17 | `tenant/dto/ActivateSubscriptionPayloadDto` | `periods` `@Min(1)` sans `@Max` → `plusYears(2e9)` → `DateTimeException` 500 | `@Max(120)` (ou borne métier) | |
| H18 | `shared/startup/StartupMigrationRunner.java:68-81` | **Boot bloquant** : Mongock série sur tous les tenants dans le thread `StartupEvent` ; à N=200 → readiness probe échoue → crash-loop | Exécuter en tâche de fond (`ManagedExecutor` / 1er tick `@Scheduled`), pool borné | |
| H19 | `shared/startup/StartupMigrationRunner.java:68-70` | Curseur control-plane ouvert pendant toutes les migrations → `CursorNotFound` possible (timeout 10 min) | Matérialiser la liste tenants (`.into(new ArrayList<>())`) **avant** la boucle | |
| H20 | `shared/exception/ThrowableExceptionMapper.java:31-39` | Propage `getMessage()` des `WebApplicationException` → fuite possible de détail interne sur 5xx enveloppées | Libellé générique pour tout statut ≥ 500 ; ne propager le message que sur 4xx connus | |
| H21 | `reception/repository/DirectReceiptRepository.java:30-53` | **`listAll()`/`search()` non plafonnés** alors que les 3 repos pairs le sont (incohérence du lot ListCap) | `ListCap.warnIfCapped(... .limit(ListCap.MAX), "réceptions")` | |
| H22 | rôles référentiels : `UnitResource:42` vs `LocalityResource:43`, `VarietyResource:46`, `OperatorResource:46` | Asymétrie : `USER` peut **créer** localité/variété/opérateur mais pas les modifier/désactiver, et pas créer d'unité → pollution du référentiel sans nettoyage possible | Uniformiser la politique de rôle des référentiels « légers » | |

---

## 🟡 Dette / cohérence / maintenabilité

| id | Fichier:ligne | Problème | Correctif proposé | Décision |
|----|---------------|----------|-------------------|----------|
| M1 | `shared/persistence/IdGenerator.java` | **Code mort** : 0 appelant. Sa Javadoc dit « appel `UUID.randomUUID()` direct interdit, injecter ce générateur », mais les **20 services** appellent `UuidCreator.getTimeOrderedEpoch()` directement. Faux signal de conformité | Trancher : adopter `IdGenerator` partout, OU le supprimer + corriger Javadoc/CLAUDE.md | |
| M2 | `{unit,locality,variety,operator}/service/*Service.java` | **Copier-coller intégral** (~400 lignes) ; `slugify` cap divergent **sans raison** (12 vs 60) ; `actor()`/`safeUserId()` dupliqués | Base générique `AbstractReferentielService`, ou helper partagé + cap uniforme | |
| M3 | `achats/service/PurchaseOrderService.java:92-100` ≡ `reception/service/DirectReceiptService.java:93-101` | `tenantVatRecoverable()` + `resolveVatRecoverable()` dupliqués mot pour mot (commentaire inclus). Règle TVA récup. en 2 exemplaires → divergence comptable silencieuse possible | Extraire `VatRecoverabilityResolver` (CDI) injecté dans les 2 | |
| M4 | repos + migrations (≈35 collections) | Noms de collections tenant non centralisés (`"stock_items"`, `"articles"`, `"counters"` ×14, `"mongockChangeLog"` en dur) alors que `ControlPlane.Collections` existe pour le control-plane | Créer `shared/persistence/TenantCollections` (jumeau) | |
| M5 | tous les `auditEvt(.target("..."))` | Types d'entité d'audit en littéraux dispersés (`unit`, `sale`, `article`, …) | Constantes `AuditTargetType.*` (le projet a déjà `AuditEventType`) | |
| M6 | `auth/service/JwtIssuer.java:73` | `issueImpersonationToken(...)` : forge de JWT super-admin **jamais câblée** → surface de sécurité dormante | Câbler (avec garde) ou retirer | |
| M7 | `stock/service/StockService.java:707` | `snapshotBySite(...)` : doublon mort de `snapshotBySiteAsItems(...)` (~35 lignes + requêtes BD inutiles, 1ʳᵉ passe écrasée par `snapshotAt`) | Supprimer le code mort | |
| M8 | divers | `PurchaseOrderService:321 attachInvoice` (sans endpoint/test) ; `PlatformSettingsService:195` accesseurs morts ; `me/dto/TenantActivityDto` ≡ `tenant/dto/TenantActivityDto` ; `BigDecimalSafe` code mort | Nettoyer / consolider dans `shared/dto` | |
| M9 | `migrations/M011:96-106` | Codes SYSCOHADA seedés en littéral alors que `accounting/entity/SyscohadaAccounts` les définit (le code de service est propre, seul le seed duplique) | Pointer le seed sur `SyscohadaAccounts.*` | |
| M10 | `shared/money/MoneyFormatter.java:83` | `@CacheResult(cacheName="currency-descriptor")` en littéral — seul cache échappant à `CacheNames` (et au `ConstantsConsistencyCheck`) | `CacheNames.CURRENCY_DESCRIPTOR` | |
| M11 | `accounting/.../JournalPieceRefService` | Numérotation sur `Year.now()` (fuseau serveur) ≠ `piece.date` → trous/chevauchements de séquence FEC à la bascule d'année | Numéroter sur l'année de `piece.date` | |
| M12 | `accounting/service/AccountingService.reverseFrom` | Ne persiste jamais `reversedFromPieceId` (traçabilité contre-passation perdue) | Persister le champ | |
| M13 | référentiels unicité code | Unicité **sensible à la casse** (`kg` vs `KG` coexistent) sur unit/locality/variety/operator | Index unique sur `code` normalisé (lowercase) ou collation insensible à la casse | |
| M14 | `replace` versionné × 4 repos | `matchedCount=0` ne distingue pas « version périmée » de « document supprimé » → message « réessayez » trompeur + réessai en boucle si le doc a disparu | Distinguer 409 (conflit) vs 404/410 (disparu) via `countDocuments(_id)` | |
| M15 | config `MongoClient` / yml | **À vérifier** : write-concern du client tenant. Si < `acknowledged`/`majority`, le lock optimiste (`matchedCount`) est cosmétique | Confirmer `w: majority` (ou au moins acknowledged) | |

---

## 🟢 Mineurs

- `StockResource.java:105` / `AccountingResource.java:104,268` / `TenantsResource.java:104` : pagination query non clampée (`?limit=-1`, `perPage` « max 100 » documenté mais non imposé) → clamp 1–500.
- `@QueryParam("q")` recherche sans `@Size` (Sale/Purchase/Manufacturing/DirectReceipt) → `@Size(max=200)`.
- `StockResource.java:83 parseInstantOrNow` : avale une date invalide → snapshot « maintenant » au lieu de 400 (incohérent avec `exportMovements`).
- Chaînes settings non bornées (`StorageSettingsUpsertDto`, `NotificationSettingsUpsertDto`, `PaymentProviderUpsertDto.code` sans `@Pattern` alors qu'il sert de segment d'URL, `EmailSettingsUpsertDto`).
- `accounting/dto/BankAccountUpsertDto:11 syscohadaAccount` sans `@Pattern` numérique (alors que `ExpenseTypeUpsertDto:22` impose `^\d{2,8}$`).
- Codes référentiel sans pattern (`RegionUpsertDto:15`, `CityUpsertDto:20`, `SiteUpsertDto.countryCode` devrait être `^[A-Z]{2}$`).
- `BeanQualityCheckUpsertDto.cutTestSampleCount` sans `@Min(0)`.
- `ManufacturingOrderEntity` : annulation depuis `COMPLETED` sans vérifier que le PF n'a pas déjà été vendu/consommé → OUT compensatoire `force=true` → stock PF négatif silencieux (lié à C2).
- `ListCap.warnIfCapped` : `size >= MAX` → faux positif si exactement MAX éléments (sans troncature réelle). Acceptable, ou requêter `MAX+1`.
- `formatPeriod` (LotTrace) perd l'année sur mois croisés années différentes ; `CANCELLED → "pending"` dans le mapping de trace.
- `bd()` via `doubleValue()` perd la précision au-delà de 2⁵³.
- Référentiels `update()`/`setActive()` : `updatedAt` retourné (mémoire) ≠ persisté (µs d'écart).

---

## Config / ops (relevé séparément — hors code Java)

- CORS prod sans garde anti-`*`.
- Fallback file-storage `local` non bloqué en prod.
- Clé de chiffrement à défaut vide héritée en prod.
- Commentaire prod « aucun défaut » contredit par `MAILER_PORT:587` / `start-tls:REQUIRED`.
- `quarkus.http.limits.max-body-size` non configuré (cf. H4).

---

## Ordre d'action suggéré (quand tu auras tranché)

1. **Quick wins sûrs** : C7 + bornes validation marquées `À CORRIGER`, parses gardés (H1/H2), `periods @Max` (H17), `DirectReceipt` ListCap (H21), GeoJSON (H3).
2. **🔴 corruptions** (après re-vérification) : C1/C2/C3 (CMUP), C5 (RD montants), C6 (QC double entrée), C4 (BC TVA déséquilibre).
3. **Opérationnel** : H18/H19 (`StartupMigrationRunner`), M15 (write-concern).
4. **Dette** : M1 (`IdGenerator`), M2/M3 (factorisation), M4 (`TenantCollections`).
