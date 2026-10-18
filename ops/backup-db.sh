#!/usr/bin/env bash
# Backup de la base de datos (corre en el droplet, lo dispara cron).
#
#   ./ops/backup-db.sh
#
# Genera un dump en formato "custom" (comprimido, restaurable con pg_restore) en
# /var/backups/taxiapp y conserva los últimos KEEP. NO es la copia fuera del
# droplet: de eso se encarga ops/pull-backups.ps1 desde tu PC.
#
# Variables (todas opcionales):
#   CONTAINER   nombre del contenedor de la base   (taxiapp-db)
#   BACKUP_DIR  carpeta de destino                 (/var/backups/taxiapp)
#   KEEP        cuántos backups se conservan       (7)

set -euo pipefail

CONTAINER="${CONTAINER:-taxiapp-db}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/taxiapp}"
KEEP="${KEEP:-7}"

mkdir -p "$BACKUP_DIR"
# Si existe el usuario `taxibackup` (sin sudo ni docker, solo para copiar los dumps a
# otra máquina con pull-backups.ps1) la carpeta es 750 con su grupo: puede LEER y
# nadie más entra. Si el grupo no existe, o este usuario no pertenece a él, se
# queda en 700: un permiso de más nunca debe romper el backup.
if getent group taxibackup > /dev/null && chgrp taxibackup "$BACKUP_DIR" 2> /dev/null; then
  chmod 750 "$BACKUP_DIR"
else
  chmod 700 "$BACKUP_DIR"
fi

stamp="$(date +%Y%m%d-%H%M%S)"
final="$BACKUP_DIR/taxiapp-$stamp.dump"
partial="$final.partial"

# Si algo falla a mitad de camino no queda un archivo a medias que parezca un
# backup válido.
trap 'rm -f "$partial"' EXIT

docker exec "$CONTAINER" pg_dump -U taxiapp -d taxiapp --format=custom --compress=6 > "$partial"

# Un dump que no se puede leer no es un backup: se verifica antes de aceptarlo.
docker exec -i "$CONTAINER" pg_restore --list < "$partial" > /dev/null

mv "$partial" "$final"

# Retención: se borran los más viejos.
ls -1t "$BACKUP_DIR"/taxiapp-*.dump 2>/dev/null | tail -n +$((KEEP + 1)) | xargs -r rm -f --

echo "$(date -Is) backup ok: $final ($(du -h "$final" | cut -f1))"
