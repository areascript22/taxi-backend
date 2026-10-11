# CLAUDE.md — Servidor TaxiGo (Spring Boot)

Reglas que hay que seguir **siempre** al trabajar en este proyecto. El detalle
de cómo está armado y por qué está en [`ARCHITECTURE.md`](ARCHITECTURE.md);
léelo antes de tocar una feature.

Stack: Spring Boot 4, Java 21, Maven, Firebase Admin SDK. Sin base de datos
propia. Los comentarios y los mensajes de log están en **español**.

---

## 1. Arquitectura

Un paquete por funcionalidad (`ride`, `chat`, `account`, `driveradmin`,
`notification`, `identity`, `common`). Dentro, cuatro capas:

```
api ──▶ application ──▶ domain ◀── infra
```

- **`domain`**: Java puro. Modelo, reglas y **puertos** (interfaces). Sin Spring,
  sin Firebase, sin servlets.
- **`application`**: casos de uso. Usa los puertos del `domain`. Nunca el SDK de
  Firebase.
- **`infra`**: implementa los puertos. **Es el único lugar donde se usa el SDK de
  Firebase** (más `common/firebase` y `common/security`).
- **`api`**: controllers y registros de request/response. Solo llama a
  `application`.

**Entre features, solo se conoce el `domain` del otro.** Si necesitas algo de
otra feature, esa feature expone un puerto en su `domain` y lo implementa su
capa de aplicación (ver `UserNotifier`, `ActiveRideLookup`). Para reaccionar a
algo que pasó en otra feature, usa un evento de dominio (`RideAccepted`).

Todo esto lo hace cumplir `ArchitectureRulesTest`: si falla, el diseño está mal,
no el test.

---

## 2. Reglas de código

### Reglas de negocio = funciones puras en el dominio
Una regla recibe el estado actual y decide; no lee ni escribe nada. Ejemplo:
`RideTransitions.cancel(ride, callerUid)`. El repositorio la aplica de forma
atómica y puede reejecutarla, así que **no puede tener efectos secundarios**.

### Errores
- Se lanzan **solo** excepciones de `common.error`: `InvalidInputException`
  (400), `ForbiddenException` (403), `NotFoundException` (404),
  `ConflictException` (409), `ExternalServiceException` (500).
- **Los controllers no tienen `try/catch`.** `GlobalExceptionHandler` traduce.
- No crees una clase de excepción por operación.
- Los mensajes de las excepciones de 4xx le llegan al cliente: nada sensible. El
  detalle de un 5xx va al log (el cliente ve un texto genérico).
- Los adaptadores de `infra` traducen sus fallos a estas excepciones; el SDK de
  Firebase no se filtra hacia arriba.

### Identidad y seguridad
- La identidad sale **siempre** del token: `@AuthenticationPrincipal
  AuthenticatedUser`. Nunca de un campo del body, un header o un path.
- El acceso por rol se declara con `@PreAuthorize`. Un `@PreAuthorize` de método
  **reemplaza** al de la clase (no se suma).
- Los permisos que dependen de datos (¿es conductor aprobado?, ¿es parte del
  chat?) los decide el caso de uso, no un rol del token.

### Configuración
- Tiempos, límites y datos de despliegue van en `@ConfigurationProperties`
  (`TaxiappProperties`, `FirebaseProperties`), no en constantes privadas.
- **Los secretos no se versionan** (`secrets/` está en `.gitignore`). Nunca
  pongas un uid, email o clave en el código.

### Firebase
- Toda llamada con timeout (`firebase.operation-timeout`). Nada de `.get()` sin
  límite.
- Los `Transaction.Handler` de Realtime Database solo viven en
  `RealtimeDbRideRepository`.

### Estilo
- DTOs y modelos como `record`.
- Comentarios que explican el **porqué**, no el qué.
- Logs con prefijo de contexto: `RideDebug |`, `ChatDebug |`,
  `DriverAdminDebug |`, `AccountDebug |`, `NotificationDebug |`,
  `IdentityDebug |`, `AuthDebug |`, `AppVersionDebug |`, `ErrorDebug |`. Un
  feature nuevo crea el suyo (`<Feature>Debug |`).
- Los contratos JSON con las apps (nombres de campos, URLs, códigos de estado)
  **no se cambian** sin cambiar las apps. Los `*Response` conservan los nombres
  que lee Flutter.

---

## 3. Tests obligatorios

Todo cambio viene con su test. No es opcional.

| Cambias… | Test |
|---|---|
| Una regla de negocio | Test de `domain`, sin Firebase ni Spring |
| Un caso de uso | Test de `application` con puertos en memoria |
| Un endpoint o su comportamiento HTTP | Test de **caracterización** (`characterization/`) |
| Un adaptador de `infra` | Test del mapeo/mensaje que construye |
| La estructura | `ArchitectureRulesTest` |

- Los tests de caracterización **no se modifican** para que pasen. Si un cambio
  los rompe, o el cambio es un error o es un cambio de comportamiento que hay
  que decidir explícitamente. Solo los `bug_conocido_*` se actualizan, y solo
  cuando se corrige ese bug a propósito.
- Antes de dar algo por terminado, verifica que el test **detecta** el error:
  rompe la regla a propósito y comprueba que falla.
- Si al escribir un test descubres un bug real en código no relacionado con el
  cambio, no lo arregles por tu cuenta: documéntalo y avisa.

```bash
# Hace falta JDK 21 (la terminal puede traer otro Java).
JAVA_HOME=<ruta-al-jdk-21> ./mvnw test
```

El servidor tiene que arrancarse **desde esta carpeta** (`server/taxiapp`): la
ruta por defecto de la credencial de Firebase es relativa.

---

## 4. Checklist: endpoint o caso de uso nuevo

- [ ] La regla es una función pura en `domain` (y tiene su test).
- [ ] El caso de uso está en `application` y no importa nada de Firebase.
- [ ] Lo que necesita del almacenamiento es un método de un puerto del `domain`.
- [ ] La implementación en `infra` traduce los fallos a `ExternalServiceException`.
- [ ] El controller no tiene `try/catch` y la identidad sale del token.
- [ ] Los errores son excepciones de `common.error`.
- [ ] Tiene test de caracterización HTTP (camino feliz + cada error).
- [ ] Si es una feature nueva: está en la lista de `ArchitectureRulesTest`.
- [ ] `./mvnw test` pasa completo.

---

## 5. Qué no se commitea

- `secrets/` y `target/`: ya están en `.gitignore`.
- `*.log` y `taxiapp.tar` (187 MB): **todavía no** están en `.gitignore`; no los
  agregues.
- `deploy/` (runbook del droplet): hoy está sin versionar. Revisa que no lleve
  secretos antes de decidir agregarlo.

Antes de un `git add`, revisa con `git status`: nunca `git add .` a ciegas.
