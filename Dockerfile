# ─────────────────────────────────────────────────────────────────────
#  Dockerfile multi-stage pour Cabosse ERP (Quarkus 3.35 / Java 21)
#
#  Stratégie : compiler le JAR DANS l'image (stage builder), puis ne
#  garder que le runtime minimal avec JRE 21 (stage runtime). Aucun
#  artefact pré-construit n'est attendu sur l'hôte — Coolify se contente
#  de `git pull` + `docker compose up --build`.
#
#  Optimisation cache :
#   1. On copie d'abord les fichiers Gradle qui changent peu
#      (wrapper, properties, build.gradle, settings.gradle). On déclenche
#      un résolution de dépendances pour mettre en cache la couche.
#   2. Puis seulement on copie le code source et on construit.
#
#  Cela évite de re-télécharger Maven Central à chaque petit changement
#  de code.
# ─────────────────────────────────────────────────────────────────────

# ─── STAGE 1 — BUILDER ────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /workspace

# 1) Copier UNIQUEMENT le wrapper Gradle et les fichiers de config
#    de build. Cette couche change rarement → cache long.
COPY gradlew /workspace/
COPY gradle /workspace/gradle
COPY settings.gradle build.gradle gradle.properties /workspace/

# Rendre le wrapper exécutable (utile si on est sur un OS hôte sans bit x)
RUN chmod +x ./gradlew

# 2) Préchauffer le cache des dépendances (téléchargement Maven Central).
#    `--no-daemon` car on est dans un container one-shot.
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# 3) Maintenant on copie le code source — chaque modif invalide ici.
COPY src /workspace/src

# 4) Build du JAR Quarkus (fast-jar layout par défaut).
#    Skip tests : Coolify fait du déploiement, pas du CI. La CI tourne
#    ailleurs (GitHub Actions ou local). Si tu veux tests dans Coolify,
#    retire `-x test`.
RUN ./gradlew --no-daemon build -x test

# ─── STAGE 2 — RUNTIME ────────────────────────────────────────────────
# JRE seul (~80 MB compressé), pas le JDK.
FROM eclipse-temurin:21-jre

# curl pour le healthcheck, openssl pour le bootstrap des clés JWT.
# Utilisateur non-root pour faire tourner l'app.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl openssl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 1001 cabosse \
 && useradd  --system --uid 1001 --gid cabosse cabosse \
 && mkdir -p /secrets \
 && chown cabosse:cabosse /secrets \
 && chmod 700 /secrets

WORKDIR /app

# Quarkus fast-jar : 4 répertoires/fichiers à recopier.
# On copie d'abord les couches stables (lib/, quarkus/) puis app/ qui
# change à chaque build. Cela permet à Docker de réutiliser les couches
# inférieures sur les rebuilds incrémentaux.
COPY --from=builder --chown=cabosse:cabosse /workspace/build/quarkus-app/lib/      /app/lib/
COPY --from=builder --chown=cabosse:cabosse /workspace/build/quarkus-app/quarkus/  /app/quarkus/
COPY --from=builder --chown=cabosse:cabosse /workspace/build/quarkus-app/app/      /app/app/
COPY --from=builder --chown=cabosse:cabosse /workspace/build/quarkus-app/quarkus-run.jar /app/quarkus-run.jar

# Entrypoint qui auto-génère les clés JWT au 1er démarrage si absentes.
COPY --chown=cabosse:cabosse docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

# Variables d'environnement par défaut. Toutes sont écrasables via
# le `docker-compose.yml` ou l'UI Coolify.
ENV LANGUAGE='en_US:en' \
    LANG='en_US.UTF-8' \
    JAVA_OPTS_APPEND="-Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager" \
    QUARKUS_PROFILE=prod

# Le port 8088 est le défaut applicatif (cf. application.yml).
# Coolify mappera ce port via Traefik automatiquement.
EXPOSE 8088

USER cabosse

# Healthcheck — par défaut on teste le endpoint OpenAPI (smallrye-openapi
# est dans les deps, donc /q/openapi répond sans auth). Quand
# quarkus-smallrye-health sera ajouté aux dépendances, basculer sur
# /q/health/ready (plus précis, inclut Mongo, scheduler, etc.).
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://localhost:8088/q/openapi -o /dev/null || exit 1

ENTRYPOINT ["/app/entrypoint.sh"]
