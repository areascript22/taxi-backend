# Operación: base de datos, backups y restauración

Todo esto asume el stack de `compose.yaml` (contenedores `taxiapp-db` y
`taxiapp`) en el droplet, con los archivos en `/opt/taxiapp`.

## 1. Puesta en marcha en el droplet

1. Instalar Docker Engine + plugin compose.
2. Copiar al droplet: `compose.yaml`, `.env` (a partir de `.env.example`),
   `ops/` y la credencial de Firebase.
3. Credencial de Firebase: dejarla fuera del repo y que solo la lea el usuario
   del contenedor (uid 10001):

   ```bash
   sudo chown 10001:10001 /opt/taxiapp/secrets/firebase-adminsdk.json
   sudo chmod 400 /opt/taxiapp/secrets/firebase-adminsdk.json
   ```

   Ajusta `FIREBASE_CREDENTIALS_FILE` en `.env` a esa ruta.
4. Levantar:

   ```bash
   cd /opt/taxiapp
   docker compose up -d
   docker compose logs -f app   # esperar "Started TaxiappApplication"
   ```

5. El servidor escucha solo en `127.0.0.1:8080`; Caddy (en el host) es quien
   lo expone con TLS. La base **no publica puertos**.
6. `.env` lleva `POSTGRES_PASSWORD`. Sin ella, el contenedor de Postgres no
   arranca (a propósito).

## 2. Backup diario en el droplet

`ops/backup-db.sh` hace `pg_dump` en formato custom, lo valida con
`pg_restore --list` y conserva los últimos 7 en `/var/backups/taxiapp`.

```bash
sudo mkdir -p /var/backups/taxiapp
chmod +x /opt/taxiapp/ops/*.sh
# crontab -e
0 3 * * * /opt/taxiapp/ops/backup-db.sh >> /var/log/taxiapp-backup.log 2>&1
```

> Un backup en el mismo disco **no protege** de perder el droplet. Para eso
> está el paso siguiente.

## 3. Copia a tu PC (Windows)

`ops/pull-backups.ps1` baja por `scp` los dumps nuevos a tu PC y conserva los
últimos 30.

```powershell
.\ops\pull-backups.ps1 -Server usuario@IP_DEL_DROPLET
# destino por defecto: $HOME\taxiapp-backups
```

Para automatizarlo, crea una tarea programada de Windows (Programador de
tareas → acción "Iniciar un programa": `powershell.exe`, argumentos
`-NoProfile -File C:\ruta\ops\pull-backups.ps1 -Server usuario@IP`). Necesita
acceso por clave SSH al droplet, sin contraseña interactiva.

## 4. Restaurar

DESTRUCTIVO: reemplaza la base por el contenido del dump.

```bash
cd /opt/taxiapp
./ops/restore-db.sh /var/backups/taxiapp/taxiapp-YYYYmmdd-HHMMSS.dump
# pide escribir "restaurar"; detiene la app, recrea la base y la vuelve a levantar
```

Para un dump bajado a tu PC, súbelo primero al droplet con `scp`.

Uso no interactivo (automatización): `RESTORE_CONFIRM=restaurar ./ops/restore-db.sh <dump>`.

Se restaura sobre una base nueva (drop + create), no con `pg_restore --clean`:
con PostGIS, `--clean` intenta borrar la extensión y falla.

## 5. Ensayo de restauración

Un backup que nunca se restauró no es un backup. Hazlo **antes** de necesitarlo
y repítelo cuando cambie el esquema:

1. Crear un dato de prueba, correr `backup-db.sh`.
2. Borrar el dato.
3. Correr `restore-db.sh` con ese dump.
4. Comprobar que el dato volvió, que `flyway_schema_history` está intacta y que
   `GET /api/public/app-version?...` responde 200.

Ensayado en local el 2026-10-11: dato recuperado, migraciones intactas,
servidor de vuelta en ~10 s.

## 6. Firewall

Docker publica puertos saltándose `ufw`. Por eso `compose.yaml` solo publica
`127.0.0.1:8080` y la base no publica nada. No agregues `ports:` al servicio
`db`. Para acceso a la base, entra por SSH y usa `docker exec -it taxiapp-db
psql -U taxiapp -d taxiapp`.

## 7. Desarrollo local

```bash
docker compose -f compose.yaml -f compose.dev.yaml up -d db
```

Deja la base en `127.0.0.1:5442` (no 5432: en Windows suele estar ocupado por
un Postgres instalado). `application-dev.properties` ya apunta ahí.
