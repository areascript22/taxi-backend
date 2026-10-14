#!/usr/bin/env bash
# Restaura la base de datos desde un dump de backup-db.sh.
#
#   ./ops/restore-db.sh /var/backups/taxiapp/taxiapp-20261011-030000.dump
#
# DESTRUCTIVO: borra la base actual y la reemplaza por el contenido del dump.
# Pide confirmación. Detiene el servidor mientras restaura y lo vuelve a
# levantar al terminar.
#
# Se restaura sobre una base NUEVA (drop + create) y no con `pg_restore --clean`:
# con PostGIS, "--clean" intenta borrar la extensión y falla por los objetos que
# dependen de ella.

set -euo pipefail

CONTAINER="${CONTAINER:-taxiapp-db}"
DUMP="${1:-}"

if [[ -z "$DUMP" || ! -f "$DUMP" ]]; then
  echo "Uso: $0 <archivo.dump>" >&2
  exit 1
fi

cd "$(dirname "$0")/.."

echo "Se va a REEMPLAZAR la base de datos del contenedor '$CONTAINER' por:"
echo "  $DUMP"
if [[ "${RESTORE_CONFIRM:-}" == "restaurar" ]]; then
  # Uso no interactivo (automatización / pruebas): RESTORE_CONFIRM=restaurar
  answer="restaurar"
else
  read -r -p "Escribe 'restaurar' para continuar: " answer
  answer="${answer%$'\r'}"   # tolera una terminal que envía CRLF
fi
if [[ "$answer" != "restaurar" ]]; then
  echo "Cancelado."
  exit 1
fi

docker compose stop app

docker exec "$CONTAINER" psql -U taxiapp -d postgres -v ON_ERROR_STOP=1 \
  -c "DROP DATABASE IF EXISTS taxiapp WITH (FORCE)" \
  -c "CREATE DATABASE taxiapp OWNER taxiapp"

docker exec -i "$CONTAINER" pg_restore -U taxiapp -d taxiapp --no-owner --exit-on-error < "$DUMP"

docker compose start app
echo "Restauración completa."
