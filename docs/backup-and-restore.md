# Sauvegarde et restauration — Cabosse ERP

**Référence** : NEIBA-ARCH-2026-004 · Septembre 2026
**Backlog** : SAAS-20 à SAAS-25
**Statut** : règle non négociable, à respecter dans tout le code Java backend.

Deux archives coexistent, et elles ne servent pas à la même chose. Les confondre produit soit une sauvegarde inexploitable, soit une destruction de données.

| | Archive de structure | Sauvegarde de plateforme |
|---|---|---|
| Sert à | déplacer un client, éprouver une sauvegarde | remonter un serveur après incident |
| Portée | une structure | tout le serveur |
| Restauration | **crée** une structure neuve | **remplace** l'existant |
| Route | `/api/v1/admin/tenants/{id}/archive` | `/api/v1/admin/platform/archive` |
| Code | `com.ntech.cabosse.tenant.transfer` | `com.ntech.cabosse.platform.transfer` |
| Accès | rôle plateforme | rôle plateforme **et** secret serveur |

---

## 1. Les deux registres de fichiers

C'est le piège principal, et il a produit un défaut silencieux entre le 18 et le 23/09/2026 : **toutes les archives sont sorties sans un seul fichier**, avec un manifeste annonçant sereinement zéro.

Une structure range ses binaires à deux endroits, comme le décrit `file-storage.md` §2 :

- `<tenant_db>.cloud_files` — pièces métier : documents de membres, justificatifs de bons de commande, pièces d'OD, documents d'exercice.
- `cabosse_control.cloud_files` — fichiers de plateforme, dont **le logo de la structure**.

La règle qui piège est dans `FileUploadService` :

```java
file.tenantId = scope == CloudFileScope.TENANT ? tenantContext.tenantId() : null;
```

Un fichier de périmètre plateforme porte donc **`tenantId = null`**, par construction. Chercher les fichiers d'une structure par `tenantId` dans le plan de contrôle ne rend jamais son logo, et ne rend rien du tout si elle n'a que des pièces métier.

**Règle.** Tout code qui rassemble les fichiers d'une structure parcourt les deux registres, et rattache les fichiers de plateforme par la référence que porte la structure (`tenant.branding.logoFileId`), jamais par `tenantId`.

---

## 2. Archive de structure

### Contenu

```
manifest.json              d'où elle vient, jusqu'où le schéma était migré
tenant/<collection>.jsonl  toute la base d'exploitation
control/<collection>.jsonl la tranche du plan de contrôle (voir CONTROL_SLICES)
files/<fileId>             les binaires, des deux registres
```

Le binaire est nommé par son identifiant de fichier, jamais par son chemin de stockage : ce chemin appartient au serveur d'origine et ne vaut plus rien ailleurs. La restauration le réécrit.

### Exclusions assumées

`TenantArchiveLayout.EXCLUDED` les nomme, et le manifeste les rend. Une archive qui se tait sur ses trous laisse croire qu'elle est complète.

- `refresh_tokens` — les restaurer ressusciterait des sessions ouvertes ailleurs.
- `tenant_backups` — le journal des sauvegardes appartient au serveur, pas au tenant.

### Restauration

Elle **crée** : nouvel identifiant, nouvelle base, nouveau raccourci. Elle n'écrase rien. Trois refus, tous avant la moindre écriture :

- raccourci déjà pris ;
- un identifiant de l'archive existe déjà sur ce serveur (`refuseIfAlreadyPresent`) ;
- archive prise après une migration que ce serveur ne connaît pas — ses documents ont une forme que le code ne sait pas lire, et le charger produirait des lectures fausses sans erreur.

Au moindre échec en cours de route, `undo` défait : une restauration à moitié faite laisse une structure fantôme que personne ne sait lire ni supprimer.

---

## 3. Sauvegarde de plateforme

### Contenu

```
manifest.json                            chaque structure emportée, sa base, ses collections
control/<collection>.jsonl               le plan de contrôle entier
tenants/<databaseName>/<collection>.jsonl la base de chaque structure
files/<fileId>                           tous les binaires, dédupliqués
```

Rien n'est filtré. Une sauvegarde qui choisit ce qu'elle garde ne répond plus de ce qu'elle rend.

### Deux pièges de construction

**La structure de l'éditeur pointe sur le plan de contrôle.** Son `databaseName` vaut `cabosse_control`. Sans l'écarter de la boucle des structures, l'archive contient le plan de contrôle deux fois, et la restauration le supprime au titre d'une structure avant de l'avoir rétabli. Export et restauration sautent donc explicitement `ControlPlane.DATABASE`.

**Un zip refuse deux entrées de même nom.** Un binaire atteignable depuis deux registres ferait échouer l'export sur un doublon au lieu de rendre une archive. D'où le jeu d'identifiants déjà écrits.

### Restauration : ce qu'elle détruit

C'est le geste le plus destructeur du produit. Il remplace le plan de contrôle et la base de chaque structure de l'archive. Tout ce qui a été saisi depuis la sauvegarde est perdu, **pour tous les clients à la fois**.

Ordre volontaire : les bases de structures d'abord, le plan de contrôle ensuite. Si une base échoue, le plan de contrôle n'a pas bougé et le serveur reste cohérent avec ce qu'il servait.

Les `refresh_tokens` sont effacés à la fin. Ils avaient été accordés contre un état que la restauration vient de supprimer, et pourraient appartenir à des comptes qui n'existent plus. Chacun se reconnecte.

### Deux barrières, et il en faut deux

Le rôle `PLATFORM_ADMIN` **ne suffit pas**. Il est porté par des comptes de travail, et l'opération efface précisément les comptes qui l'autorisent. S'y ajoute un secret posé hors de l'application :

```yaml
application:
  platform-archive:
    restore-secret: ${PLATFORM_RESTORE_SECRET:}
```

```bash
openssl rand -base64 48        # puis poser PLATFORM_RESTORE_SECRET sur le serveur
```

Le secret voyage dans l'en-tête `X-Platform-Restore-Secret`, jamais dans une URL ni dans un corps de formulaire. Il est comparé avec `MessageDigest.isEqual` : une comparaison qui s'arrête au premier caractère différent dit combien de caractères sont justes, ce qui suffit à retrouver un secret sur une route qu'on peut appeler en boucle.

**Secret absent de la configuration : la route refuse.** Mieux vaut une restauration impossible qu'une restauration ouverte à qui obtient un jeton d'administrateur.

### Remonter deux fois de suite

Le stockage local écrit en `CREATE_NEW`, et le chemin d'un binaire ne dépend que de son identifiant. La restauration efface donc le chemin avant d'écrire. Sans cela, la seconde tentative échouait sur les fichiers posés par la première, **après** que les bases aient été remplacées — or remonter un serveur deux fois de suite est le cas normal quand la première tentative a mal tourné.

---

## 4. En-têtes et CORS

Un en-tête absent de la liste CORS n'est pas refusé par le serveur : il est refusé par le **navigateur**, au contrôle préalable, avant que la requête n'atteigne quoi que ce soit. Rien n'apparaît dans les journaux, et l'écran annonce une panne de réseau alors que le réseau va très bien.

Le piège a mordu deux fois : `Idempotency-Key` sur les flux d'argent, puis `X-Platform-Restore-Secret` le 23/09/2026. Aucun test d'API ne peut le voir, puisque le contrôle préalable est un comportement du navigateur.

`CorsHeadersTest` lit désormais les fichiers eux-mêmes et tient deux choses :

- chaque en-tête que le front envoie est autorisé ;
- **dev, qa et prod ne divergent pas.** Le cas dangereux n'est pas d'oublier partout, c'est d'ajouter en développement et de livrer sans : tout marche chez soi, et la fonction est morte chez le client.

Un nouvel en-tête personnalisé s'ajoute à `CorsHeadersTest.REQUIRED` **dans le même commit** que le code qui l'envoie. C'est le seul endroit qui relie les deux dépôts.

---

## 5. Ce que les tests tiennent, et ce qu'ils ne tiennent pas

`TenantArchiveTest` et `PlatformArchiveTest` couvrent le contenu des archives, les refus, et la restauration d'une structure — y compris le **téléchargement effectif** du logo restauré, plutôt qu'un lien que rien ne sert.

Le chemin nominal de la restauration complète **n'est pas automatisé**, et ce n'est pas un oubli : elle supprime le plan de contrôle que partage toute la suite, et emporterait les autres tests avec elle. Elle se vérifie à la main sur une instance locale. Dernière vérification le 23/09/2026, depuis l'écran du back-office : 3 structures, 36 065 documents, 3 fichiers, un repère créé après la sauvegarde correctement effacé, logo retéléchargé intact.

---

## 6. Récapitulatif des règles

| Interdit | À la place |
|---|---|
| Rassembler les fichiers d'une structure par `tenantId` seul | Parcourir les deux registres (§1) |
| Emporter le chemin de stockage tel quel | Nommer par identifiant de fichier, réécrire le chemin à la restauration |
| Traiter `cabosse_control` comme une base de structure | L'écarter de la boucle, il a sa propre section |
| Ouvrir la restauration complète au seul rôle plateforme | Exiger en plus le secret serveur, comparé en temps constant |
| Ajouter un en-tête CORS en dev seulement | Les trois environnements, plus l'entrée dans `CorsHeadersTest` |
| Écrire un binaire sans libérer le chemin | Effacer d'abord : le stockage refuse d'écraser |
| Annoncer une archive complète en taisant ses exclusions | Les nommer dans le manifeste et à l'écran |
