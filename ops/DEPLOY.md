# Despliegue automático (rama `qa` → droplet)

```
merge dev → qa
  └─ GitHub Actions: tests → docker build → push ghcr.io/areascript22/taxi-backend:<sha>
       └─ ssh deploy@droplet → ops/deploy.sh <imagen>
            backup → pull → reemplaza solo `app` → verifica → (si falla) rollback
```

- Droplet: `178.128.78.76` (2 GB) · Dominio: `https://taxigo.juniorz.dev`
- Perfil de Spring `dev` → proyecto Firebase **dev** (`taxigo-dev-96d02`).
- La credencial de Firebase **solo vive en el droplet**; GitHub nunca la ve.

## Puesta en marcha (una sola vez)

### A. Droplet (como root)

Docker y el firewall (22, 80, 443) ya estaban. **Caddy NO se instala en el
host**: corre como contenedor, definido en `compose.yaml`.

```bash
# 1. Swap de respaldo (los límites de compose dejan poco margen)
fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab

# 2. Usuario de despliegue (sin sudo, sin contraseña)
adduser --disabled-password --gecos "" deploy
usermod -aG docker deploy
install -d -o deploy -g deploy -m 700 /home/deploy/.ssh
chown deploy:deploy /opt/taxiapp
install -d -o deploy -g deploy /var/backups/taxiapp

# 3. Migrar desde el stack viejo (taxiapp:1.0.0 + Caddy, sin Postgres).
#    `down` SIN -v: los volúmenes con los certificados de Caddy se conservan.
cd /opt/taxiapp
docker compose down
mv docker-compose.yml docker-compose.yml.old
mv Caddyfile Caddyfile.old
```

> Estar en el grupo `docker` equivale a ser root en la máquina. Por eso esta
> clave es exclusiva de CI y el usuario no tiene sudo.

### B. Tu PC (PowerShell)

```powershell
# Clave exclusiva de CI, sin passphrase
ssh-keygen -t ed25519 -N '""' -f $HOME\.ssh\taxiapp-ci -C "github-actions-taxiapp"

# Autorizarla en el droplet (la clave pública)
type $HOME\.ssh\taxiapp-ci.pub | ssh root@178.128.78.76 "cat >> /home/deploy/.ssh/authorized_keys && chown deploy:deploy /home/deploy/.ssh/authorized_keys && chmod 600 /home/deploy/.ssh/authorized_keys"

# Huella del host (va al secret DROPLET_HOST_KEY)
ssh-keyscan -t ed25519 178.128.78.76

# Archivos de compose (la credencial de Firebase dev ya está en el droplet)
scp compose.yaml root@178.128.78.76:/opt/taxiapp/
scp -r ops root@178.128.78.76:/opt/taxiapp/
```

### C. Droplet otra vez (como root)

```bash
# Copia de la credencial dev que ya estaba (el contenedor corre como uid 10001)
cp /opt/taxiapp/secrets/taxigo-dev-firebase-adminsdk.json /opt/taxiapp/secrets/firebase-adminsdk.json
chown 10001:10001 /opt/taxiapp/secrets/firebase-adminsdk.json
chmod 400 /opt/taxiapp/secrets/firebase-adminsdk.json
chown -R deploy:deploy /opt/taxiapp/ops /opt/taxiapp/compose.yaml
chmod +x /opt/taxiapp/ops/*.sh

# .env (nunca se versiona): ver .env.example
cat > /opt/taxiapp/.env <<EOF
POSTGRES_PASSWORD=$(openssl rand -base64 24)
SPRING_PROFILES_ACTIVE=dev
FIREBASE_CREDENTIALS_FILE=/opt/taxiapp/secrets/firebase-adminsdk.json
TAXIAPP_ADMIN_FOUNDER_UID=<uid>
TAXIAPP_ADMIN_FOUNDER_EMAIL=<correo>
EOF
chown deploy:deploy /opt/taxiapp/.env && chmod 600 /opt/taxiapp/.env

# Backup diario (crontab -e, como deploy)
# 0 3 * * * /opt/taxiapp/ops/backup-db.sh >> /var/log/taxiapp-backup.log 2>&1
```

### D. GitHub

1. Repo `areascript22/taxi-backend` → Settings → Environments → **New: `qa`**.
2. Secrets del environment `qa`:

| Secret | Valor |
|---|---|
| `DROPLET_HOST` | `178.128.78.76` |
| `DROPLET_USER` | `deploy` |
| `DROPLET_SSH_KEY` | contenido completo de `taxiapp-ci` (la privada) |
| `DROPLET_HOST_KEY` | la línea de `ssh-keyscan` |

3. Settings → Actions → General → Workflow permissions: basta con "Read
   repository contents" (cada job declara los permisos que necesita).

### E. Primer despliegue

Hacer merge de `dev` a `qa` (o *Run workflow* en la pestaña Actions). Después:

```bash
ssh deploy@178.128.78.76 "docker ps && docker logs --tail 30 taxiapp"
curl -i "https://taxigo.juniorz.dev/api/public/app-version?app=driver&version=1.0.0"   # 200
curl -i https://taxigo.juniorz.dev/api/rides/passenger/active                          # 401 = bien
```

En el log debe aparecer `Firebase Admin inicializado correctamente. Proyecto: taxigo-dev-96d02`.

## Día a día

| Quiero… | Hago… |
|---|---|
| Desplegar | merge `dev` → `qa` |
| Volver atrás | Actions → *Deploy QA* → *Run workflow* → `redeploy_sha` = sha anterior |
| Ver logs | `ssh deploy@… "docker logs -f taxiapp"` |
| Ver qué imagen corre | `ssh deploy@… "grep TAXIAPP_IMAGE /opt/taxiapp/.env"` |

## Reglas a respetar

- **El rollback de imagen no deshace migraciones Flyway.** Las migraciones
  tienen que ser compatibles hacia atrás: agregar columnas/tablas, no
  renombrar ni borrar de golpe (hacerlo en dos despliegues).
- Cada despliegue reinicia el proceso y **pierde los temporizadores en
  memoria** (expiración de 35 s, anillos de despacho). Pendiente: barrido
  periódico.
- Cambios en `compose.yaml` u `ops/` se aplican solos: CI los copia en cada
  despliegue. No edites esos archivos a mano en el droplet.
