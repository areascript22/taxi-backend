# Trae a tu PC los backups de la base de datos del droplet.
#
#   .\ops\pull-backups.ps1 -Server taxibackup@178.128.78.76
#   .\ops\pull-backups.ps1 -Server taxibackup@178.128.78.76 -Destination D:\respaldos\taxigo -Keep 60
#
# Descarga solo los que todavia no tenes y borra de tu PC los mas viejos
# (conserva los ultimos -Keep). Es seguro correrlo todos los dias.
#
# Se conecta como el usuario `taxibackup` del droplet: sin sudo, sin docker, solo puede
# LEER /var/backups/taxiapp. NO uses `deploy` ni `root` para esto: `deploy` esta en
# el grupo docker, que equivale a root. La llave (-IdentityFile) no tiene passphrase
# a proposito para poder programarlo; por eso su unico poder es leer los dumps.
#
# Necesita el cliente OpenSSH de Windows (el mismo que usa deploy.ps1).
#
# Los dumps contienen datos personales y quedan SIN CIFRAR en tu PC: guardalos en
# una unidad cifrada.

param(
    [Parameter(Mandatory = $true)]
    [string]$Server,

    [string]$Destination = (Join-Path $HOME 'taxiapp-backups'),

    [string]$RemoteDir = '/var/backups/taxiapp',

    [string]$IdentityFile = (Join-Path $HOME '.ssh\id_backup'),

    [int]$Keep = 30
)

$ErrorActionPreference = 'Stop'

New-Item -ItemType Directory -Force -Path $Destination | Out-Null

# Solo esa llave y sin preguntar nada: si falla, que falle claro y no se quede
# esperando una contrasena que nadie va a escribir (p. ej. en una tarea programada).
$sshOptions = @('-i', $IdentityFile, '-o', 'IdentitiesOnly=yes', '-o', 'BatchMode=yes')

# Nombres de los dumps en el droplet (los .partial son backups a medias).
$remoteFiles = & ssh @sshOptions $Server "ls -1 $RemoteDir/taxiapp-*.dump 2>/dev/null"
if ($LASTEXITCODE -ne 0 -and -not $remoteFiles) {
    Write-Host "No se pudo listar $RemoteDir en $Server (o todavia no hay backups)." -ForegroundColor Yellow
    exit 1
}

$downloaded = 0
foreach ($remote in @($remoteFiles)) {
    if ([string]::IsNullOrWhiteSpace($remote)) { continue }

    $name = Split-Path $remote -Leaf
    $local = Join-Path $Destination $name
    if (Test-Path $local) { continue }

    # Se baja a un temporal y se renombra: un corte a mitad de camino no deja
    # un archivo truncado que parezca un backup valido.
    $temp = "$local.partial"
    & scp -q @sshOptions "${Server}:$remote" $temp
    if ($LASTEXITCODE -ne 0) {
        Remove-Item -Force -ErrorAction SilentlyContinue $temp
        throw "Fallo la descarga de $name"
    }
    Move-Item -Force $temp $local
    $downloaded++
    Write-Host ("Descargado: {0} ({1:N1} MB)" -f $name, ((Get-Item $local).Length / 1MB))
}

# Retencion local: se conservan los ultimos $Keep.
$old = Get-ChildItem -Path $Destination -Filter 'taxiapp-*.dump' |
    Sort-Object Name -Descending |
    Select-Object -Skip $Keep
foreach ($file in $old) {
    Remove-Item -Force $file.FullName
    Write-Host "Borrado (retencion): $($file.Name)" -ForegroundColor DarkGray
}

$total = (Get-ChildItem -Path $Destination -Filter 'taxiapp-*.dump').Count
Write-Host "Listo: $downloaded nuevos, $total backups en $Destination"
