#!/bin/sh
# ─────────────────────────────────────────────────────────────────────
#  Entrypoint Cabosse ERP backend — auto-bootstrap des secrets JWT.
#
#  Au premier démarrage, si /secrets/jwt-private.pem n'existe pas,
#  une paire RSA 2048 est générée DANS le volume persistant. Les
#  démarrages suivants réutilisent les clés existantes.
#
#  Le volume `cabosse-secrets` (docker-compose) garantit la
#  persistance entre redéploiements Coolify.
# ─────────────────────────────────────────────────────────────────────
set -e

SECRETS_DIR="/secrets"
PRIVATE_KEY="${SECRETS_DIR}/jwt-private.pem"
PUBLIC_KEY="${SECRETS_DIR}/jwt-public.pem"

if [ ! -f "${PRIVATE_KEY}" ] || [ ! -f "${PUBLIC_KEY}" ]; then
  echo "[entrypoint] Génération initiale de la paire JWT RSA-2048..."
  openssl genrsa -out "${PRIVATE_KEY}" 2048 2>/dev/null
  openssl rsa -in "${PRIVATE_KEY}" -pubout -out "${PUBLIC_KEY}" 2>/dev/null
  chmod 600 "${PRIVATE_KEY}" "${PUBLIC_KEY}"
  echo "[entrypoint] Clés générées dans ${SECRETS_DIR}/."
else
  echo "[entrypoint] Clés JWT trouvées dans ${SECRETS_DIR}/, réutilisation."
fi

# shellcheck disable=SC2086
exec java ${JAVA_OPTS_APPEND} -jar /app/quarkus-run.jar
