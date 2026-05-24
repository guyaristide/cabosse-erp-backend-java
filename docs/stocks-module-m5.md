# Module Stocks (M5) — conventions et garde-fous

**Référence** : NEIBA-ARCH-2026-005 · Mai 2026
**Statut** : backend + frontend livrés 2026-05-22. Validation E2E à dérouler en pilote (ticket #261).

Ce document fige les règles structurantes du module Stock. Toute évolution
qui s'en écarte doit être justifiée explicitement (note de décision séparée).

---

## 1. Modèle de données

### 1.1 `StockItemEntity` (collection `stock_items`)

Vue agrégée par couple `(articleId, siteId)` — **identité métier** unique
(index unique en BD). Existe pour lecture O(1) de la position
(quantité + CMUP) sans rejouer le journal.

| Champ | Type | Notes |
|---|---|---|
| `id` | UUID | `@BsonId` |
| `articleId` | UUID | FK articles |
| `siteId` | UUID | FK sites |
| `articleCode/Name/Unit/Type` | snapshots | rafraîchis à chaque mouvement |
| `quantity` | BigDecimal | peut être < 0 si `ALLOW_NEGATIVE_STOCK` |
| `cmupFcfa` | BigDecimal | 0 si quantity == 0 |
| `alertThreshold` | BigDecimal? | hérité d'Article.alertThreshold à la première entrée, modifiable par site |
| `lastMovementAt` | Instant | pour tri "récents" |
| `version` | long | lock optimiste (utile si on quitte un jour le pipeline atomique) |

**`articleType` est l'enum `ArticleType`** (pas un String comme dans
`ArticleEntity.type`). Conversion `ArticleType.valueOf(article.type)` à
chaque snapshot.

### 1.2 `StockMovementEntity` (collection `stock_movements`)

Ligne de journal **immuable**. Toute correction = nouveau mouvement
compensatoire.

Champs clés au-delà des snapshots :
- `quantitySigned` : signée selon `kind` (positive IN/OPENING/TRANSFER_IN,
  négative OUT/TRANSFER_OUT, signe libre ADJUSTMENT).
- `unitPriceFcfa` :
  - IN/OPENING/TRANSFER_IN : PU d'achat fourni
  - OUT/TRANSFER_OUT : CMUP courant snapshoté (= cmupAfterFcfa, sortie ne change pas le CMUP)
  - ADJUSTMENT : null
- `quantityAfter` / `cmupAfterFcfa` : état du StockItem **après** ce mouvement (snapshot figé).
- `sourceType` (enum) + `sourceRef` (ex. "RD-2026-0007") + `sourceEntityId` (UUID opaque) — traçabilité.
- `transferId` : lie OUT/IN d'un transfert. Null sinon.
- `occurredAt` : date d'effet (peut différer de `createdAt` pour saisies rétroactives).

### 1.3 `MovementKind` (enum)
`IN`, `OUT`, `ADJUSTMENT`, `OPENING`, `TRANSFER_OUT`, `TRANSFER_IN`.

### 1.4 `MovementSource` (enum)
`PURCHASE_ORDER`, `DIRECT_RECEIPT`, `OPENING`, `INVENTORY`, `PRODUCTION`,
`SALE`, `TRANSFER`, `MANUAL`.

---

## 2. CMUP — règle de calcul

**Formule (CDC §5.3, mémo `project_cmup_rule_5_3`)** :
```
CMUP_after = (qty_before × CMUP_before + qty_in × PU_in) / (qty_before + qty_in)
```
- Par couple `(articleId, siteId)`.
- Recalculé sur **chaque entrée** (IN, OPENING, TRANSFER_IN).
- **Inchangé** sur les sorties et ajustements.
- Vaut 0 si `qty_after <= 0`.

### 2.1 Implémentation atomique
`StockService.applyEntryAtomic` utilise un **pipeline d'agrégation Mongo**
via `findOneAndUpdate` avec `List<Bson>` en paramètre `update`. Voir
`cmupExpression(...)` pour l'expression `$let` imbriquée qui calcule
`(oldQty*oldCmup + inQty*inPu) / newQty` directement côté serveur Mongo.

**Pourquoi pas une transaction multi-doc** : éviter d'imposer un replica
set en dev. Le pipeline `findOneAndUpdate` est atomique par nature.

### 2.2 Précision
`CMUP_SCALE = 4` (4 décimales suffisent pour FCFA, monnaie sans subdivision).

---

## 3. Branchements depuis les modules métier

Tout service qui modifie le stock **passe par `StockService.applyMovement(MovementInput)`**.
Pattern d'intégration :

```java
@Inject StockService stockService;

// Après l'opération métier qui doit impacter le stock :
for (Line line : entity.lines) {
    stockService.applyMovement(new MovementInput(
            line.articleId, entity.siteId,
            MovementKind.IN,                      // ou OUT selon le cas
            line.quantity, line.unitPriceFcfa,
            MovementSource.DIRECT_RECEIPT,        // ou PURCHASE_ORDER, PRODUCTION, SALE…
            entity.ref, entity.id,
            null, null, null, Instant.now()
    ));
}
```

**Best-effort sur `siteId == null`** : ne pas appeler le service, log
silencieux. Évite les cas pathologiques (entités créées avant que les
sites soient en place).

### 3.1 Branchements actifs (2026-05-21)

| Service | Trigger | Mouvement posé |
|---|---|---|
| `DirectReceiptService.create` | À la création de la RD | `IN` par ligne (PU = ligne.unitPriceFcfa) |
| `DirectReceiptService.cancel` | À l'annulation | `OUT` compensatoire par ligne (PU = ligne.unitPriceFcfa initial) |
| `PurchaseOrderService.deliver` | À la livraison du BC | `IN` par ligne (PU = ligne.unitPriceFcfa) |
| `PurchaseOrderService.cancel` | À l'annulation, **si previousStatus == DELIVERED** | `OUT` compensatoire par ligne |

### 3.2 Branchements à venir
- `ProductionService.commitOF` (M3) : `OUT` matière + `IN` produit fini
- `SaleService.confirmDelivery` (M4) : `OUT` produit fini

### 3.3 Règle de compensation
Les contre-passations utilisent le **PU initial** de la ligne (pas le
CMUP courant). Cela remet l'état du stock dans la configuration qu'il
aurait eu si la transaction d'origine n'avait jamais eu lieu — fidèle
comptablement, traçable.

**Flag `force` interne** : les compensations passent un
`MovementInput.force = true` qui bypasse le contrôle
`ALLOW_NEGATIVE_STOCK`. Cohérent : si la marchandise reçue a déjà été
transférée ou consommée entre-temps, la compensation doit pouvoir
poser un OUT qui amène le stock en négatif. L'utilisateur voit
ensuite l'anomalie et corrige via un ajustement physique. Le flag
n'est exposé nulle part dans l'API REST — il est strictement
interne aux services compensation.

---

## 4. Drapeaux et seuils

| Constante | Valeur | Comment changer |
|---|---|---|
| `StockService.ALLOW_NEGATIVE_STOCK` | `false` | Pour v1.1 : à migrer vers `TenantSettings`. Les `ADJUSTMENT` contournent par définition (ils peuvent acter un négatif détecté physiquement). |
| `StockService.CMUP_SCALE` | `4` | Précision interne. Affichage UI = 0 décimale (FCFA pas de subdivision). |

---

## 5. Audit

5 event types dans `AuditEventType` (catégorie `OPERATIONS`) :
- `STOCK_MOVEMENT_RECORDED` — déclenché par défaut (IN, OUT)
- `STOCK_OPENING_RECORDED` — déclenché par les OPENING
- `STOCK_TRANSFER_RECORDED` — déclenché par les TRANSFER_OUT et TRANSFER_IN
- `STOCK_INVENTORY_COUNTED` — réservé aux mvts générés par `recordInventoryBatch`
- `STOCK_ADJUSTMENT_RECORDED` — ADJUSTMENT hors inventaire (saisie manuelle)

Le mapping kind → eventType est fait dans `StockService.recordMovementAudit`.
Description audit : `<kind> <article> · <qty> <unit> · CMUP après <cmupAfter> · source <ref>`.

---

## 6. Endpoints REST

```
GET    /api/v1/stocks?siteId&q&type&belowThreshold
GET    /api/v1/stocks/snapshot?siteId&asOf            ← photo à date (lecture seule)
GET    /api/v1/stocks/{articleId}
GET    /api/v1/stocks/{articleId}/sites/{siteId}
GET    /api/v1/stocks/{articleId}/sites/{siteId}/movements?limit&skip
POST   /api/v1/stocks/movements                       (USER+ADMIN, MovementUpsertDto)
POST   /api/v1/stocks/transfer                        (USER+ADMIN, TransferDto)
POST   /api/v1/stocks/opening                         (ADMIN,      OpeningBatchDto)
POST   /api/v1/stocks/inventory                       (USER+ADMIN, InventoryBatchDto)
GET    /api/v1/stocks/export?siteId&q&type&belowThreshold&format
GET    /api/v1/stocks/movements/export?siteId&q&from&to&format
```

Format export : `csv`, `xlsx`, `pdf`. Audit `DATA_EXPORTED` posé.

### 6.1 Snapshot à date

`asOf` accepte deux formats :
- `YYYY-MM-DD` (interprété comme fin de journée UTC, pour englober tous les mouvements du jour)
- ISO complet `2026-05-15T14:30:00Z`

Le service `snapshotBySiteAsItems(siteId, asOf)` :
1. Charge tous les `StockItemEntity` courants du site.
2. Pour chaque article, appelle `snapshotAt(articleId, siteId, asOf)` qui :
   - Repart de la `quantity` courante et soustrait les `quantitySigned` des mouvements postérieurs à `asOf`.
   - Récupère le `cmupAfterFcfa` du dernier mouvement ≤ `asOf` (ou 0 si aucun).
3. Renvoie des `StockItemResponseDto` enrichis (articleCode/Name/Unit/Type/alertThreshold du présent + qty/CMUP du passé).

Le frontend bascule en lecture seule sur cette vue : aucune mutation autorisée tant que `asOf` est posé.

---

## 7. Indexes posés par M008

`stock_items` :
- unique `(articleId, siteId)` → identité métier
- `(siteId, articleName)` → liste alpha par site
- `(siteId, articleType)` → filtres tabs UI

`stock_movements` :
- unique `ref`
- `(articleId, siteId, occurredAt:-1)` → historique fiche
- `(siteId, occurredAt:-1)` → journal site
- `sourceEntityId` (sparse) → retrouve les mvts d'une RD/BC/OF
- `transferId` (sparse) → paire OUT/IN d'un transfert

---

## 8. Hors scope v1

Documenté dans le plan (`PLAN_M5_Stocks.md` §11). Rappel :
- Encours / WIP (semi-finis)
- Tracking par lot pour valorisation (FIFO/LIFO)
- Réservations / engagements
- Multi-unités (conversions)
- Inventaire tournant programmé
- Import de mouvements historiques en masse

---

## 9. Surfaces frontend exposées

Le frontend consomme l'API via la façade typée
`cabosse_web_saas_app/src/lib/api/facade/stocks.ts` (cf. `stocksApi.list`,
`snapshot`, `createMovement`, `transfer`, `opening`, `inventory`,
`exportStocksUrl`, `exportMovementsUrl`).

Les hooks TanStack Query vivent dans
`cabosse_web_saas_app/src/features/stocks/api/hooks.ts` :
- `useStockItems(filters)` — liste filtrée
- `useStockSnapshot(siteId, asOf)` — photo à date
- `useStockByArticle(articleId)` — tous les sites
- `useStockItem(articleId, siteId)` — fiche détail
- `useStockMovements(articleId, siteId, limit, skip)` — journal paginé
- `useCreateStockMovement` / `useTransferStock` / `useOpeningStock` / `useApplyInventory` — mutations
- `useCmupLookup()` — utilisé par M3 (production) et M4 (ventes) pour valoriser les lignes au CMUP courant. **Ne pas changer sa signature externe** sans coordonner avec ces modules.

Pages :
- `/app/stocks` — liste + tabs ArticleType + filtre "sous seuil" + sélecteur date + export
- `/app/stocks/:articleId?siteId=…` — fiche détail avec historique et panneau multi-sites
- `/app/stocks/amorcage/nouveau` — amorçage initial batch
- `/app/stocks/inventaire/nouveau` — inventaire physique (écart auto, valorisation live)
- `/app/stocks/transfert/nouveau` — transfert inter-sites

---

*Document — création 2026-05-21, mise à jour 2026-05-22 (clôture sprint M5 hors E2E). Mettre à jour si une règle change.*
