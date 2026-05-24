# Site-scoping — modèle physique de la chaîne logistique

**Stack** : Quarkus 3.x · MongoDB 7.x (database-per-tenant)
**Périmètre** : tenant DB (jamais control plane)
**Référence** : NEIBA-ARCH-2026-002 · Mai 2026

Ce document définit comment l'entité **Site** se rattache aux autres
documents métier (stock, achats, production, ventes, livraisons). Il est
load-bearing pour M2 (Achats), M3 (Production), M4 (Ventes), M5 (Stocks)
et toute migration future qui touche au stock physique.

---

## Table des matières

1. [Modèle conceptuel](#1-modèle-conceptuel)
2. [Cartographie des rattachements](#2-cartographie-des-rattachements)
3. [Contraintes par type de site](#3-contraintes-par-type-de-site)
4. [Comportement du bandeau "site actif"](#4-comportement-du-bandeau-site-actif)
5. [Immutabilité du `siteId`](#5-immutabilité-du-siteid)
6. [Cycle de vie d'un site](#6-cycle-de-vie-dun-site)
7. [Règles imposées](#7-règles-imposées)
8. [Checklist d'implémentation par module](#8-checklist-dimplémentation-par-module)

---

## 1. Modèle conceptuel

Un **Site** est un emplacement physique du tenant où des marchandises
existent, transitent ou changent d'état. Deux types coexistent :

| Type             | Rôle                                                 | Compte dans `maxSites` ? |
|------------------|------------------------------------------------------|--------------------------|
| `TRANSFORMATION` | Usine, atelier — où la matière devient produit fini  | Oui                      |
| `SALES_POINT`    | Boutique, dépôt commercial — destinataire de stock fini | Oui (quota global)    |

Le site est la **clé de partitionnement physique** : le stock, les
ordres de fabrication, les bons de commande et les ventes sont toujours
rattachés à un site précis. Sans `siteId`, on ne peut pas répondre à la
question fondamentale « combien de cacao reste-t-il à Méagui ? ».

Le `siteId` est un UUID v7 référençant la collection `sites` de la base
du tenant courant. Pas de cross-tenant possible — le scope est garanti
par la base.

---

## 2. Cartographie des rattachements

### 2.1 Un seul site (1 FK `siteId`)

Le cas général. Le document existe dans l'orbite d'un site et un seul.

| Document             | Champ      | Sémantique                                          |
|----------------------|------------|-----------------------------------------------------|
| Fiche stock          | `siteId`   | Site où la quantité est constatée                   |
| Mouvement de stock   | `siteId`   | Site où le mouvement physique a lieu                |
| Ordre de fabrication | `siteId`   | Site **TRANSFORMATION** où se déroule la production |
| Bon de Commande      | `siteId`   | Site destinataire (où réceptionner)                 |
| Bon de Réception     | `siteId`   | Hérité du BC parent                                 |
| Vente                | `siteId`   | Site où la vente est faite                          |
| Encaissement         | `siteId`   | Hérité de la vente                                  |
| Créance              | `siteId`   | Hérité de la vente                                  |
| Inventaire physique  | `siteId`   | Site sur lequel on compte                           |

### 2.2 Deux sites (origine + destination)

Un document qui modélise un **transfert physique** porte deux FK.

| Document         | Origine        | Destination                                 |
|------------------|----------------|---------------------------------------------|
| Bon de Livraison (BL) | `fromSiteId` | `toSiteId` (transfert interne) **ou** `clientId` + `deliveryAddress` |
| Transfert stock  | `fromSiteId`   | `toSiteId`                                  |

Règles :
- Si `toSiteId` est posé, `clientId` doit être null, et vice-versa.
- `fromSiteId !== toSiteId` (un BL "transfert" qui ne bouge pas est une erreur métier).
- Les deux sites doivent appartenir au même tenant.

### 2.3 Aucun site — tenant-scoped global

Les **référentiels** vivent au niveau du tenant, pas du site. Ils sont
visibles et utilisables depuis n'importe quel site.

- Articles (matières premières, produits finis)
- Recettes (BOM, nomenclatures)
- Clients, fournisseurs
- Types de dépenses, destinations / activités
- Plans comptables (SYSCOHADA)
- Utilisateurs

Un référentiel n'a **jamais** de `siteId`. Si demain on veut un client
"exclusif" à un site, on ajoute un champ `allowedSiteIds: UUID[]` —
mais ce n'est pas le cas au MVP.

### 2.4 Cas hybride — `UserEntity.allowedSiteIds`

L'utilisateur n'est pas site-scopé (il est tenant-scopé), mais il porte
une liste blanche RBAC : `allowedSiteIds: '*' | UUID[]`.

- `'*'` (admin, direction) : voit tous les sites du tenant.
- `[id1, id2, …]` : voit uniquement les sites listés. Toute requête
  site-scopée doit filtrer côté backend sur cette liste.

---

## 3. Contraintes par type de site

Certains documents n'ont de sens que sur un type de site précis.
**Le backend doit refuser** les combinaisons invalides — pas seulement
masquer côté UI.

| Document             | TRANSFORMATION | SALES_POINT | Notes                                     |
|----------------------|:--------------:|:-----------:|-------------------------------------------|
| Ordre de fabrication | ✅             | ❌          | Une boutique ne produit pas              |
| Réception matière 1ère | ✅           | ⚠️          | Autoriser SALES_POINT pour les consommables (emballages, étiquettes) |
| Réception produit fini transféré | ✅ | ✅       | Un PF peut arriver dans une boutique     |
| Vente directe client | ✅             | ✅          | Vente d'usine ET vente boutique          |
| Inventaire physique  | ✅             | ✅          | Tout site a du stock à compter           |
| Mouvement de stock   | ✅             | ✅          | Type-agnostique                          |

**Application** : chaque service métier qui crée un document avec
`siteId` doit valider via un helper unique, p.ex. :

```java
public static void assertTransformationSite(SiteEntity site) {
    if (!"TRANSFORMATION".equals(site.type)) {
        throw new BusinessException(
            "Cette opération nécessite un site de transformation."
        );
    }
}
```

---

## 4. Comportement du bandeau "site actif"

Le sélecteur de site dans la topbar (`SiteSwitcher`) maintient un
**site actif** dans `authStore.site`. Ce site filtre par défaut tous
les écrans site-scopés.

### 4.1 Écrans filtrés automatiquement par le site actif

- Stocks (vue articles + mouvements)
- Production (liste OF, nouvelles OF)
- Ventes (liste, encaissements)
- Achats (BC en cours, réceptions à venir)
- Bons de livraison

Le hook standard est :
```ts
const siteId = useAuthStore((s) => s.site?.id);
const { data } = useQuery({
  queryKey: qk.stocks.items(tenantId, siteId),
  queryFn: () => apiFacade.stocks.list(siteId),
  enabled: !!siteId,
});
```

### 4.2 Écrans qui ignorent le bandeau

- Référentiels (matières, recettes, clients, fournisseurs, sites)
- Profil utilisateur
- Administration

### 4.3 Écrans multi-sites avec sélecteur dédié

Reporting consolidé, Direction, Comptabilité agrégée : ces écrans ont
besoin de voir tous les sites en une vue. Ils proposent leur **propre**
sélecteur ("Tous les sites · Méagui · Abidjan") qui ne touche pas au
bandeau global.

---

## 5. Immutabilité du `siteId`

**Règle dure** : `siteId` est posé à la création d'un document et **ne
peut plus changer**.

### Pourquoi

Le `siteId` est référencé par le stock site-scopé. Une vente déjà
décrémentée du site A (la quantité a baissé) ne peut pas migrer vers le
site B — sinon on perd la cohérence comptable.

### Comment "déplacer" une opération

Pas d'`UPDATE`. On contre-passe + on recrée :

1. Contre-passation : créer un mouvement inverse sur le site A.
2. Création : recréer le document sur le site B.
3. Audit : les deux opérations sont tracées séparément.

Idem pour les fiches stock : on n'éditele pas `siteId`, on transfère via BL.

---

## 6. Cycle de vie d'un site

### 6.1 Création

- Au provisioning d'un tenant : un site `TRANSFORMATION` "Siège"
  est créé automatiquement (`TenantProvisioningService.seedDefaultSite`).
- Manuellement : `POST /api/v1/sites` (réservé `TENANT_ADMIN`).
- Le quota `plan.maxSites` est appliqué globalement (tous types confondus).

### 6.2 Modification

- `PUT /api/v1/sites/{id}` : modifie tous les champs mutables. **Pas**
  `type` ni `code` (immutables, référencés en FK).

### 6.3 Désactivation

- `PATCH /api/v1/sites/{id}/active?value=false`.
- Le site disparaît des sélecteurs (bandeau, dropdowns, RBAC) mais
  **reste en base**. Aucune opération passée n'est modifiée.
- Aucune écriture n'est possible sur un site désactivé (validation au
  niveau de chaque service métier).

### 6.4 Suppression dure

**Pas au MVP**. La suppression dure casserait le stock historique, le
reporting, les exports comptables. Si un site doit vraiment disparaître,
on le désactive — la trace reste.

### 6.5 Audit

Toute écriture (création, modification, activation/désactivation) est
loguée dans `cabosse_control.global_audit` via `AuditService` avec
`eventType=CATALOG_UPDATED`, `targetType=site`.

---

## 7. Règles imposées

Ces règles sont **non-négociables**. Toute violation est un bug.

1. **Tout document à réalité physique porte un `siteId` dès sa création.**
   Pas de migration "on ajoutera le siteId plus tard" — ingérable.

2. **`siteId` est obligatoire et non nullable** sur les documents
   site-scopés. La validation au niveau du service métier rejette toute
   création sans site.

3. **`siteId` immutable** après création. Pas d'`UPDATE` sur ce champ.
   Pour déplacer une opération, contre-passer + recréer.

4. **Le site référencé doit exister et être actif** au moment de la
   création. Validation côté service via `SiteRepository.findById` +
   check `active=true`.

5. **Cohérence inter-site** : un BL avec `toSiteId` ne peut pas avoir
   `fromSiteId === toSiteId`. Un BC ne peut pas avoir destinationSite
   désactivé.

6. **Cross-tenant impossible** : la base elle-même garantit que le
   `siteId` référence un site du tenant courant — pas besoin de double
   check. Mais si jamais un service reçoit un `siteId` en payload, il
   doit `findById` dessus (et donc échouer si le site n'existe pas
   dans le tenant).

7. **RBAC site** : tout endpoint qui retourne ou modifie une liste
   site-scopée doit filtrer/valider contre `tenantContext.allowedSiteIds`.
   Pour l'instant tous les rôles ont `'*'` ; le filtrage devient
   load-bearing quand on aura des rôles vendeur/caissier limités.

---

## 8. Checklist d'implémentation par module

Quand on attaquera un nouveau module métier, cocher ces points :

### 8.1 M2 — Achats

- [ ] `PurchaseOrderEntity` : champ `siteId: UUID` non-nullable
- [ ] `PurchaseOrderUpsertDto` : `siteId` requis, validation
- [ ] `PurchaseOrderService.create` : `findSiteOrThrow(siteId)` + check actif
- [ ] `PurchaseOrderResource` : `GET /api/v1/purchase-orders?siteId=…`
- [ ] Le BC ne peut pas être créé si le site est désactivé
- [ ] À la réception : `StockMovement` créé avec le même `siteId`

### 8.2 M3 — Production

- [ ] `ManufacturingOrderEntity` : `siteId` non-nullable
- [ ] Validation `site.type === TRANSFORMATION` au create + au update
- [ ] Sortie matière + entrée PF : `StockMovement.siteId === of.siteId`

### 8.3 M4 — Ventes

- [ ] `SaleEntity` : `siteId` non-nullable, type-agnostique (TRANSFORMATION ou SALES_POINT)
- [ ] La vente décrémente le stock du `siteId`
- [ ] Encaissements / créances héritent du `siteId` de la vente

### 8.4 M5 — Stocks

- [ ] `StockFicheEntity` : clé composite logique `(siteId, articleId)`
- [ ] `StockMovementEntity` : `siteId` non-nullable
- [ ] Inventaire physique : 1 inventaire = 1 site = N articles comptés
- [ ] Transfert inter-sites : `StockTransferEntity` avec `fromSiteId` +
      `toSiteId`. Génère 2 mouvements (sortie A + entrée B) en transaction.

### 8.5 Bons de livraison

- [ ] `DeliveryNoteEntity` : `fromSiteId` requis, soit `toSiteId` soit
      `clientId` requis (exclusivité)
- [ ] Si `toSiteId` posé → transfert interne, génère 2 mouvements stock
- [ ] Si `clientId` posé → livraison client, génère 1 mouvement sortie + lien vente

---

## Annexes

### A. Référence du `SiteEntity`

Voir `com.ntech.cabosse.site.entity.SiteEntity`. POJO Mongo dans la base
tenant. Champs clés :

- `id: UUID` (BsonId)
- `type: String` ("TRANSFORMATION" | "SALES_POINT")
- `code: String` (slug immutable, FK)
- `name: String`
- `active: boolean`
- coordonnées GPS optionnelles : `latitude`, `longitude`
- contact : `phone`, `email`, `managerName`
- libre : `addressLine`, `description`, `openingHours`

### B. Patterns de requête

```java
// Liste des fiches stock pour le site actif
List<StockFicheEntity> list = stockFicheRepo
    .listBySite(tenantContext.activeSiteId());

// Création d'un mouvement — siteId obligatoire dans le DTO
public StockMovement create(StockMovementUpsertDto p) {
    SiteEntity site = sites.findById(p.siteId())
        .orElseThrow(() -> new NotFoundException("Site introuvable."));
    if (!site.active) {
        throw new BusinessException("Site désactivé.");
    }
    // ... création du mouvement avec site.id
}
```

### C. À surveiller dans les prochaines migrations

- Quand on aura les vraies migrations Mongock tenant avec données métier,
  toujours initialiser `siteId` dès la définition du schéma. **Ne pas**
  livrer un schéma sans `siteId` puis le rajouter — ça force une
  re-write de tous les documents existants pour rebrancher le stock.

- Si on doit un jour différencier le quota par type (ex. plan Pro :
  3 transformations + 10 points de vente), ajouter `maxTransformationSites`
  et `maxSalesPoints` sur `PlanEntity`, garder `maxSites` comme fallback
  global. La validation dans `SiteService.assertWithinQuota` devra alors
  splitter par type.
