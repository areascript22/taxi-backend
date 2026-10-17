# Staging: VM Ubuntu en la laptop + Cloudflare Tunnel

```
dev ──merge──▶ qa ──▶ GitHub Actions (build-qa.yml): tests → build → push a GHCR
                                  │
                                  ▼  imagen  ghcr.io/areascript22/taxi-backend:<sha>
                          VM de staging (a mano): ./ops/deploy.sh <imagen>
                                  │
   testers ──HTTPS──▶ Cloudflare ══túnel══▶ cloudflared ──HTTP──▶ app:8080
            staging.taxiriobamba.com
```

- **No hay despliegue automático a la VM**: GitHub no puede alcanzarla (está
  detrás de NAT/CGNAT) y, además, no conviene que staging cambie solo mientras
  hay testers usándolo. Lo despliega quien enciende la VM.
- Perfil de Spring `staging` → proyecto Firebase **dev**. Solo datos de prueba.
- Un solo ambiente por máquina: este `compose.yaml` sirve igual aquí y en el
  droplet. Lo que cambia es el `.env` y qué servicios se nombran al levantar.

## Puesta en marcha (una sola vez)

La VM, el hardening y Docker ya están hechos. Lo que falta para poder desplegar
imágenes de GHCR:

```bash
# Carpeta de backups que usa ops/backup-db.sh
sudo install -d -o "$USER" -g "$USER" -m 700 /var/backups/taxiapp

# Marca que el proxy NO es Caddy (para que deploy.sh no lo levante)
echo "DEPLOY_PROXY=tunnel" >> ~/taxiapp/.env

# Credencial para bajar imágenes de GHCR: un PAT clásico con SOLO read:packages.
# docker login lo pide por teclado y lo guarda en ~/.docker/config.json.
docker login ghcr.io -u <tu-usuario-github>
```

`.env` de la VM (ver `.env.example`): `SPRING_PROFILES_ACTIVE=staging`,
`POSTGRES_PASSWORD`, `FIREBASE_CREDENTIALS_FILE`, `CLOUDFLARE_TUNNEL_TOKEN`,
`DEPLOY_PROXY=tunnel`, y el fundador (dev).

## Cada ronda de pruebas

1. Merge de `dev` a `qa`. En Actions → *Build QA image*, el resumen del job
   muestra la imagen y el comando exacto.
2. Enciende la VM (VirtualBox → Iniciar, con ventana abierta) y entra: `ssh staging`.
3. Copia `compose.yaml` y `ops/` si cambiaron (desde el repo, en tu PC):
   `scp -r compose.yaml ops staging:taxiapp/`
4. Despliega **por sha** (no por `:qa`, para poder volver atrás):
   ```bash
   cd ~/taxiapp && ./ops/deploy.sh ghcr.io/areascript22/taxi-backend:<sha>
   ```
   Hace backup, baja la imagen, reemplaza solo `app`, espera a que responda y, si
   no responde, vuelve a la imagen anterior.
5. Si algún servicio no está arriba:
   `sudo docker compose up -d db app cloudflared` (nombrándolos; un
   `docker compose up -d` a secas levantaría también Caddy, que aquí sobra).

Para apagar: *Máquina → Cerrar → Enviar la señal de apagado*, o `sudo shutdown now`.

## Volver atrás
Despliega el sha anterior con el mismo comando. El rollback de imagen no deshace
migraciones de Flyway: deben ser compatibles hacia atrás.
