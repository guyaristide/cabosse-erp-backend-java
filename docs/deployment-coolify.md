# Déploiement Coolify — Cabosse ERP backend

**Référence** : NEIBA-DEPLOY-2026-001 · Mai 2026

Flow cible : `git push` → Coolify webhook → `git pull` sur le serveur →
`docker compose up -d --build` → multi-stage Dockerfile qui compile le
JAR sur place puis monte l'image runtime. **Aucun registry d'images
externe**.

---

## 1. Vue d'ensemble

```
┌──────────────┐  git push   ┌────────────┐   webhook   ┌──────────────┐
│  Développeur │ ──────────> │   GitHub   │ ──────────> │   Coolify    │
└──────────────┘             └────────────┘             │   server     │
                                                        │              │
                                                        │ 1. git pull  │
                                                        │ 2. compose   │
                                                        │    up --build│
                                                        │ 3. multi-    │
                                                        │    stage     │
                                                        │    Dockerfile│
                                                        │ 4. JRE       │
                                                        │    runtime   │
                                                        └──────────────┘
```

**Avantages** :
- Pas de registry à payer / sécuriser (pas de Docker Hub, pas de GHCR).
- L'image runtime ne contient ni Gradle ni le source — juste la JRE 21
  + le fast-jar Quarkus (~250 MB compressé).
- Cache Docker fortement optimisé : tant que `build.gradle` et
  `gradle.properties` ne changent pas, les dépendances ne sont pas
  re-téléchargées.

**Inconvénients** (à connaître) :
- Le serveur Coolify doit avoir assez de RAM pour la phase de compilation
  (Gradle + Quarkus = ~2 GB pendant le build). Min recommandé : 4 GB total.
- Le premier build prend ~3-5 minutes (téléchargement Maven Central).
  Les suivants : ~30-60 secondes (cache des dépendances).

---

## 2. Pré-requis serveur Coolify

- Coolify v4+ installé (`curl -fsSL https://cdn.coollabs.io/coolify/install.sh | bash`).
- Docker + Docker Compose v2 (installés par Coolify).
- Au moins 4 GB de RAM (2 GB de buffer pour Gradle pendant le build,
  + 1 GB pour Mongo runtime, + buffer OS).
- Au moins 10 GB d'espace disque (image Docker + volumes uploads + mongo
  + cache Gradle qui survit dans `~/.gradle/caches` du builder).

---

## 3. Clés JWT — auto-bootstrap au premier démarrage

Le backend signe et vérifie les JWT avec une paire RSA. **Aucune
manipulation manuelle n'est nécessaire** : le script
[`docker/entrypoint.sh`](../docker/entrypoint.sh) génère la paire dans
le volume `cabosse-secrets` au tout premier `docker compose up`.

```sh
# Logique de l'entrypoint (résumé)
if [ ! -f /secrets/jwt-private.pem ]; then
  openssl genrsa  -out /secrets/jwt-private.pem 2048
  openssl rsa -in /secrets/jwt-private.pem -pubout -out /secrets/jwt-public.pem
  chmod 600 /secrets/*.pem
fi
exec java -jar /app/quarkus-run.jar
```

### Persistance

`cabosse-secrets` est un volume Docker **nommé**. Coolify ne le wipe
jamais — même après un `docker compose down` ou un redéploiement
complet, les `.pem` sont conservées. Le bootstrap ne se déclenche que
si les fichiers sont absents (1er boot OU volume supprimé manuellement).

### Vérifier après le 1er déploiement

```bash
docker logs cabosse-backend | head -5
# [entrypoint] Génération initiale de la paire JWT RSA-2048...
# [entrypoint] Clés générées dans /secrets/.

docker exec cabosse-backend ls -la /secrets
# -rw-------    1 cabosse cabosse  451 May 26 16:00 jwt-public.pem
# -rw-------    1 cabosse cabosse 1704 May 26 16:00 jwt-private.pem
```

### Fournir tes propres clés (optionnel)

Si tu veux contrôler la paire (par ex. pour faire matcher un IdP externe,
ou pour réutiliser une paire existante), pré-peuple le volume **avant**
le 1er déploiement :

```bash
docker volume create cabosse-secrets

docker run --rm \
  -v cabosse-secrets:/secrets \
  -v /tmp/mes-cles:/src:ro \
  alpine sh -c "cp /src/*.pem /secrets/ && chown 1001:1001 /secrets/*.pem && chmod 600 /secrets/*.pem"
```

L'entrypoint détectera les clés existantes et sautera le bootstrap.

### Rotation des clés

Pour roter (compromission, audit annuel, etc.) :

```bash
docker compose stop backend
docker run --rm -v cabosse-secrets:/secrets alpine rm -f /secrets/*.pem
docker compose start backend
# → l'entrypoint regénère une nouvelle paire automatiquement
```

**Conséquence** : tous les JWT en circulation sont invalidés (les
utilisateurs devront se reconnecter). Prévoir une fenêtre de maintenance
ou un mécanisme de double-clé si la coupure n'est pas acceptable.

---

## 4. Configuration Coolify (étape par étape)

### 4.1 Créer le projet

1. Coolify UI → **Projects** → **+ New Project** → nom "Cabosse ERP".
2. **+ New Resource** → choisir **Docker Compose**.
3. Source : **Public Git Repository** (ou Private + ajouter la clé SSH).
4. URL : ton repo `git@github.com:…/cabosse_backend.git`.
5. Branch : `main` (ou `prod` si tu sépares les branches).
6. Build pack : **Docker Compose** (auto-détecté si `docker-compose.yml`
   est à la racine).

### 4.2 Variables d'environnement

Dans l'UI Coolify, onglet **Environment Variables**, ajouter :

| Variable | Valeur | Notes |
|---|---|---|
| `CORS_ORIGINS` | `https://app.tondomaine.com` | Domaines du frontend, séparés par virgule. Pas de wildcard. |
| `APP_BASE_URL` | `https://api.tondomaine.com` | URL publique du backend (pour les liens email). |
| `MAILER_FROM` | `noreply@tondomaine.com` | |
| `MAILER_HOST` | `smtp.sendgrid.net` | Ou autre SMTP. |
| `MAILER_USERNAME` | `apikey` | (SendGrid spécifique) |
| `MAILER_PASSWORD` | `SG.xxxxxxxx` | **Marquer "Is Build Secret"**. |
| `JWT_ISSUER` | `https://api.tondomaine.com` | Doit matcher le `iss` claim. |
| `FILE_STORAGE_BACKEND` | `local` | Bascule à `s3` quand prêt. |
| `S3_*` | (vide en local) | À remplir pour S3. |

### 4.3 Domaine + SSL

Onglet **Domains** :
1. Ajouter `api.tondomaine.com`.
2. Coolify configure Traefik automatiquement + Let's Encrypt.
3. Vérifier que le DNS pointe vers l'IP du serveur Coolify (A record).

### 4.4 Webhook git

Onglet **Source** → bouton **Deploy on push**. Coolify génère une URL
de webhook à coller dans GitHub :

1. Copier l'URL webhook Coolify (forme
   `https://coolify.tondomaine.com/api/v1/deploy?uuid=…&force=false`).
2. GitHub repo → **Settings** → **Webhooks** → **Add webhook**.
3. Payload URL : coller l'URL Coolify.
4. Content type : `application/json`.
5. Event : `push` uniquement.
6. Save.

Test : faire un commit + push → vérifier que Coolify déclenche un
build dans son onglet **Deployments**.

### 4.5 Premier déploiement

1. Cliquer **Deploy** dans l'UI Coolify.
2. Coolify exécute `git pull` puis `docker compose up -d --build`.
3. Suivre les logs en direct dans l'onglet **Logs**.

Le premier build prend ~3-5 minutes. Si succès, l'app est joignable
sur `https://api.tondomaine.com/q/health` qui doit retourner `{"status":"UP"}`.

---

## 5. Vérifications post-déploiement

```bash
# Sur le serveur Coolify, dans le shell
docker ps                              # cabosse-backend + cabosse-mongo healthy
docker logs cabosse-backend --tail 50  # Quarkus démarré, Mongock migrations OK
docker exec cabosse-mongo mongosh --quiet --eval "db.adminCommand('ping')"
```

Test depuis ton poste :
```bash
curl https://api.tondomaine.com/q/health/ready
# → {"status":"UP","checks":[...]}

curl -X POST https://api.tondomaine.com/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@…","password":"…"}'
```

---

## 6. Stratégie de stockage fichiers

### 6.1 Local (défaut — démarrage rapide)

Les fichiers uploadés (images articles, etc.) vivent dans le volume
Docker `cabosse-uploads`, monté à `/var/cabosse/uploads`. Les fichiers
**survivent** aux redéploiements (Coolify ne wipe pas les volumes
nommés).

Limitations :
- Si tu scales à plusieurs containers, chaque container a son propre
  volume → incohérence.
- Pas de CDN, pas de réplication géographique.

Pour démarrer, c'est largement suffisant.

### 6.2 S3 ou compatible (production sérieuse)

Quand prêt, basculer :
1. Créer un bucket (AWS S3, Cellar, Wasabi, MinIO…).
2. Coolify env vars : `FILE_STORAGE_BACKEND=s3`, `S3_BUCKET=…`,
   `S3_REGION=…`, `S3_ACCESS_KEY=…`, `S3_SECRET_KEY=…`,
   éventuellement `S3_ENDPOINT=…` pour S3 non-AWS.
3. Redéployer. Les nouveaux uploads partent en S3.
4. **Migrer les fichiers existants** : `mc cp -r` ou équivalent depuis
   le volume local vers le bucket S3 si tu veux la continuité.

---

## 7. Mises à jour / rollback

### Mise à jour normale
Push sur la branche connectée → Coolify rebuild automatiquement.

### Rollback rapide
Coolify garde l'historique des déploiements (onglet **Deployments**).
Cliquer sur un déploiement précédent → **Redeploy**. Coolify rebuild
depuis le SHA git de ce déploiement.

### Force rebuild (si cache pourri)
```bash
# Sur le serveur Coolify, dans le dossier projet :
docker compose build --no-cache backend
docker compose up -d --force-recreate backend
```

---

## 8. Backups MongoDB

Cron job à mettre en place sur le serveur Coolify (en dehors de Coolify
pour éviter qu'un wipe projet supprime aussi les backups) :

```bash
# /etc/cron.daily/cabosse-mongo-backup
#!/bin/bash
set -e
BACKUP_DIR=/var/backups/cabosse-mongo
mkdir -p "$BACKUP_DIR"
docker exec cabosse-mongo mongodump --archive --gzip > \
  "$BACKUP_DIR/mongo-$(date +%Y%m%d).gz"
# Rotation : garder 30 jours
find "$BACKUP_DIR" -name 'mongo-*.gz' -mtime +30 -delete
```

Pour restorer :
```bash
docker exec -i cabosse-mongo mongorestore --archive --gzip --drop \
  < /var/backups/cabosse-mongo/mongo-20260524.gz
```

---

## 9. Points de vigilance

1. **Mémoire serveur pendant le build** : Gradle + Quarkus = pic à
   ~2 GB. Si le serveur Coolify a moins de 4 GB total, ça swap fort
   pendant le build (lent, voire OOM). Solution : ajouter du swap
   ou augmenter la RAM.

2. **Cache Gradle dans le builder** : chaque rebuild Docker
   re-télécharge tout si Docker n'a pas le cache (rare). Pour
   accélérer le tout-premier build, on peut prévoir un volume nommé
   pour `~/.gradle/caches` mais Coolify ne le supporte pas
   nativement en builder. Pas critique pour la suite — Docker
   réutilise les layers.

3. **`-x test`** dans le Dockerfile : Coolify ne fait pas tourner les
   tests. La CI tourne ailleurs (GitHub Actions, en local…). Pas de
   garde-fou côté déploiement — si le code casse les tests, Coolify
   le pousse quand même. À toi de garder une discipline CI séparée.

4. **Secrets JWT** : si tu changes les clés (`jwt-public.pem` /
   `jwt-private.pem`), tous les tokens existants sont invalidés.
   Prévoir une fenêtre de rotation.

5. **Migrations Mongock** : tournent au boot de l'app. Si une
   migration plante, l'app refuse de démarrer (et Coolify retentera
   en boucle, ce qui peut spammer Mongo). Surveiller les logs après
   chaque déploiement qui inclut une nouvelle migration.

6. **Mongo en single-node replica set** : le compose démarre mongo avec
   `--replSet rs0` et le healthcheck initie le RS au premier démarrage.
   C'est obligatoire car le driver Quarkus + `@Transactional` exige un
   contexte transactionnel Mongo qui n'existe qu'en RS ou sharded.
   Si tu repars d'un volume mongo créé en mode standalone (sans
   `--replSet`), supprime-le d'abord : `docker volume rm cabosse-mongo-data`.
   Côté client, le `MONGO_URI` doit contenir
   `?replicaSet=rs0&directConnection=true` (déjà dans le compose).

7. **Healthcheck via `/api/v1/health/ping`** : endpoint applicatif
   `@PermitAll` défini par `HealthResource`. Retourne 200 dès que
   Quarkus répond (ne touche pas Mongo — sonde de vivacité pure,
   suffisante pour Traefik/Coolify). Si tu vois `Unhealthy state` dans
   Coolify, lance ce diagnostic depuis le serveur :
   ```bash
   docker exec cabosse-backend curl -i http://localhost:8088/api/v1/health/ping
   # → HTTP 200 + {"ok":true,"data":"OK"}  → OK
   # → 401 / 403 → un filtre bloque malgré @PermitAll, vérifier
   #   TenantContextFilter / TenantStatusGuard (cf. note Quarkus
   #   `@Authenticated` n'est pas NameBinding)
   ```

---

*Document — création 2026-05-24. Mettre à jour si le flow Coolify
change (nouvelle version, autre orchestrateur, etc.).*
