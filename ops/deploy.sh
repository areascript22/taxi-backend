#!/usr/bin/env bash
# Despliegue de una imagen del servidor (corre en el droplet, lo dispara CI por SSH).
#
#   ./ops/deploy.sh ghcr.io/areascript22/taxi-backend:<sha> [usuario-ghcr]
#
# Qué hace, en orden:
#   1. (opcional) docker login a GHCR con el token que llega por STDIN.
#   2. Backup de la base, si ya existe (migra Flyway al arrancar: sin backup una
#      migración mala no tiene vuelta atrás).
#   3. docker pull de la imagen.
#   4. Reemplaza SOLO el contenedor `app` (la base no se toca).
#   5. Espera a que el servidor responda. Si no responde, vuelve a la imagen
#      anterior y termina con error para que el despliegue figure como fallido.
#
# La imagen desplegada queda escrita en `.env` (TAXIAPP_IMAGE): así un
# `docker compose up -d` manual posterior no la revierte.
#
# Variables (todas opcionales):
#   HEALTH_URL      endpoint público para verificar   (http://127.0.0.1:8080/api/public/app-version?app=driver&version=0.0.0)
#   HEALTH_TIMEOUT  segundos máximos de espera        (120; la JVM en 1 vCPU tarda 30-60 s)

set -euo pipefail

cd "$(dirname "$0")/.."

IMAGE="${1:?uso: deploy.sh <imagen> [usuario-ghcr]}"
GHCR_USER="${2:-}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8080/api/public/app-version?app=driver&version=0.0.0}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-120}"
ENV_FILE=".env"

# Llega por SSH desde fuera: se valida el formato antes de usarlo en cualquier
# comando. Solo registro/ruta:tag con caracteres de nombre de imagen.
if ! [[ "$IMAGE" =~ ^[a-z0-9./_-]+:[A-Za-z0-9._-]+$ ]]; then
  echo "imagen inválida: $IMAGE" >&2
  exit 2
fi

# Un solo despliegue a la vez.
exec 9>/tmp/taxiapp-deploy.lock
flock -n 9 || { echo "ya hay un despliegue en curso" >&2; exit 3; }

log() { echo "$(date -Is) deploy | $*"; }

current_image() {
  grep -E '^TAXIAPP_IMAGE=' "$ENV_FILE" 2>/dev/null | tail -n1 | cut -d= -f2- || true
}

set_image() {
  if grep -qE '^TAXIAPP_IMAGE=' "$ENV_FILE" 2>/dev/null; then
    sed -i "s|^TAXIAPP_IMAGE=.*|TAXIAPP_IMAGE=$1|" "$ENV_FILE"
  else
    printf '\nTAXIAPP_IMAGE=%s\n' "$1" >> "$ENV_FILE"
  fi
}

wait_healthy() {
  local deadline=$((SECONDS + HEALTH_TIMEOUT))
  while (( SECONDS < deadline )); do
    if [[ "$(docker inspect -f '{{.State.Running}}' taxiapp 2>/dev/null || echo false)" != "true" ]]; then
      sleep 3   # recién creado o reiniciándose: se sigue esperando hasta el tope
      continue
    fi
    if curl -fsS -o /dev/null --max-time 3 "$HEALTH_URL"; then
      return 0
    fi
    sleep 3
  done
  return 1
}

[[ -f "$ENV_FILE" ]] || { echo "falta $ENV_FILE en $(pwd) (ver .env.example)" >&2; exit 4; }

PREVIOUS="$(current_image)"

# 1. Credenciales de GHCR (el token vive solo lo que dura el job de CI).
if [[ -n "$GHCR_USER" ]]; then
  IFS= read -r token
  printf '%s' "$token" | docker login ghcr.io -u "$GHCR_USER" --password-stdin
fi

# 2. Base de datos: en el primer despliegue no existe todavía.
if [[ "$(docker inspect -f '{{.State.Running}}' taxiapp-db 2>/dev/null || echo false)" == "true" ]]; then
  log "backup previo"
  ./ops/backup-db.sh
else
  log "la base no está corriendo: se levanta (primer despliegue)"
  docker compose up -d db
  for _ in $(seq 1 40); do
    [[ "$(docker inspect -f '{{.State.Health.Status}}' taxiapp-db 2>/dev/null || true)" == "healthy" ]] && break
    sleep 3
  done
fi

# 3. Imagen.
log "pull $IMAGE"
docker pull "$IMAGE"

# 4. Reemplazo.
log "desplegando (anterior: ${PREVIOUS:-ninguna})"
set_image "$IMAGE"
docker compose up -d --no-deps app

# 5. Verificación y rollback.
if wait_healthy; then
  log "OK: $IMAGE responde"
  # Proxy: se levanta si no existe y recarga el Caddyfile por si cambió (el
  # bind mount no dispara una recreación del contenedor).
  docker compose up -d --no-deps caddy
  docker compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile || true
  docker image prune -af --filter "until=168h" > /dev/null || true
  exit 0
fi

log "FALLÓ: $IMAGE no respondió en ${HEALTH_TIMEOUT}s. Últimas líneas del log:"
docker logs --tail 40 taxiapp 2>&1 || true

if [[ -n "$PREVIOUS" && "$PREVIOUS" != "$IMAGE" ]]; then
  log "volviendo a $PREVIOUS"
  set_image "$PREVIOUS"
  docker compose up -d --no-deps app
  if wait_healthy; then
    log "rollback OK: $PREVIOUS responde"
  else
    log "ATENCIÓN: el rollback tampoco responde; revisar a mano"
  fi
else
  log "no hay imagen anterior a la que volver"
fi
exit 1
