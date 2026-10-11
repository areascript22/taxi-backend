# Arquitectura del servidor

Backend de TaxiGo (Spring Boot 4, Java 21). Lo consumen `driver_app` y
`passenger_app`. Usa Firebase (Realtime Database, Firestore, Authentication,
Storage y Cloud Messaging) mediante el Admin SDK y tiene una base PostgreSQL +
PostGIS propia, en migración (ver sección 11): hoy está vacía de datos de
negocio, solo tiene el esquema base de Flyway.

> Las reglas para escribir código en este proyecto están en [`CLAUDE.md`](CLAUDE.md).
> Este documento explica **cómo está armado y por qué**.

---

## 1. Qué hace

| Feature | Responsabilidad | Endpoints |
|---|---|---|
| `ride` | Ciclo de vida de una carrera: pedir, aceptar, cancelar, finalizar, vencer, consultar el viaje activo | `POST /api/rides/request`<br>`POST /api/rides/{passengerId}/accept`<br>`POST /api/rides/{passengerId}/arrived`<br>`POST /api/rides/{passengerId}/start`<br>`POST /api/rides/{passengerId}/cancel`<br>`POST /api/rides/{passengerId}/complete`<br>`GET /api/rides/passenger/active`<br>`GET /api/rides/driver/active` |
| `chat` | Mensajes entre pasajero y conductor durante un viaje | `POST /api/rides/{passengerId}/messages` |
| `account` | Que un usuario borre su propia cuenta | `DELETE /api/account` |
| `driveradmin` | Panel de administración: listar, aprobar/rechazar, bloquear, cambiar rol y eliminar conductores | `GET /api/drivers`<br>`GET /api/drivers/{uid}`<br>`PUT /api/drivers/{uid}/role`<br>`PUT /api/drivers/{uid}/approval-status`<br>`PUT /api/drivers/{uid}/block-status`<br>`DELETE /api/drivers/{uid}` |
| `appversion` | Decir si una versión de una app todavía se admite (versión mínima por app) | `GET /api/public/app-version?app=driver&version=1.0.7` — **público**, sin token |
| `notification` | Traducir y entregar notificaciones push | (ninguno: lo usan las demás features) |
| `identity` | La cuenta de autenticación (roles, borrado) | (ninguno) |
| `common` | Errores, configuración, seguridad, Firebase | (ninguno) |

Además hay tareas en segundo plano (vencimiento de solicitudes sin conductor,
limpieza de carreras terminadas) y un arranque que asegura los privilegios del
fundador del proyecto.

---

## 2. Estructura

Un paquete por funcionalidad. Dentro de cada una, cuatro capas:

```
com.areascript.taxiapp
 ├─ ride/
 │   ├─ api/          controllers y registros de request/response  (HTTP)
 │   ├─ application/  casos de uso: coordinan reglas y puertos
 │   ├─ domain/       modelo, reglas y PUERTOS (interfaces). Java puro.
 │   └─ infra/        implementaciones de los puertos (Firebase, Spring)
 ├─ chat/       ├─ account/   ├─ driveradmin/   ├─ notification/   ├─ identity/
 └─ common/     error · config · firebase · security
```

### Regla de dependencias

```
        api ──▶ application ──▶ domain ◀── infra
```

- `domain` no conoce a nadie: ni Spring, ni Firebase, ni servlets.
- `application` usa interfaces del `domain`; nunca el SDK de Firebase.
- `infra` es la **única** capa que usa el SDK de Firebase. Implementa las
  interfaces del `domain` (inversión de dependencias).
- `api` solo habla con `application`.

### Entre features

Una feature **solo conoce el `domain` de otra**, nunca su `application`, `infra`
ni `api`. Si una feature necesita algo de otra, esa otra expone un **puerto en su
dominio** y lo implementa su capa de aplicación:

| Necesita | Puerto (en el dominio de…) | Lo implementa |
|---|---|---|
| `chat`, `ride` avisan a un usuario | `UserNotifier` (`notification`) | `PushNotificationService` |
| `account` pregunta si hay un viaje en curso | `ActiveRideLookup` (`account`) | `RideQueryService` (`ride`) |
| `chat` pregunta por la carrera | `ChatRideLookup` (`chat`) | `RideQueryService` (`ride`) |
| `ride` valida al conductor | `DriverRepository` (`driveradmin`) | `FirestoreDriverRepository` |
| `account`/`driveradmin` usan la cuenta de Auth | `IdentityProvider` (`identity`) | `FirebaseIdentityProvider` |
| `driveradmin` borra los datos de un usuario | `UserDataCleaner` (`account`) | `FirebaseUserDataCleaner` |

Y cuando una feature **reacciona** a otra sin que esta la conozca, se usa un
evento de dominio. Hoy hay uno: `ride` publica `RideAccepted` y `chat` abre el
hilo de la carrera.

```
          ┌────────── ride ──────────┐
          │ RideLifecycleService     │── RideAccepted ──▶ chat (abre el hilo)
          │   ├─ DriverRepository ───┼──▶ driveradmin.domain
          │   └─ UserNotifier ───────┼──▶ notification.domain
          │ RideQueryService ────────┼──▶ implementa account.ActiveRideLookup
          └──────────────────────────┘             y chat.ChatRideLookup
```

### Cómo se hace cumplir

`src/test/.../architecture/ArchitectureRulesTest.java` falla el build si alguien:

- hace que `domain` dependa de Spring, Firebase o servlets;
- hace que `application` use el SDK de Firebase;
- hace que `domain`/`application` dependan de `infra`;
- hace que una feature dependa de `application`/`infra`/`api` de otra;
- usa el SDK de Firebase fuera de `infra`, `common` y `bootstrap`;
- usa `FirebaseToken` fuera de `common.security`;
- hace que un controller acceda directo a las bases de datos.

---

## 3. Dónde vive cada dato

| Dato | Base | Quién escribe | Notas |
|---|---|---|---|
| Carrera en curso `taxi_requests/{passengerId}` | Realtime Database | El **servidor** (crear, asignar, llegada, inicio, cancelar, finalizar, vencer, borrar: todas las transiciones de estado). Las **apps** escriben directo solo `driver/location` | Una carrera por pasajero. Las apps escuchan este nodo en tiempo real. Se borra ~10 s después de terminar. |
| Perfil de conductor `drivers/{uid}` | Firestore | La **app** (su perfil, `fcmToken`, `language`). El **servidor** (`role`, `approvalStatus`, `isBlocked` y sus metadatos) | Las reglas impiden que la app toque los campos de moderación. |
| Vehículo `vehicles/{id}` | Firestore | La app | El servidor lo lee para mostrarlo al pasajero y lo borra con la cuenta. |
| Perfil de pasajero `passengers/{uid}` | Firestore | La app | El servidor lee `fcmToken`/`language` para notificar. |
| Chat `chats/{rideId}` y `.../messages` | Firestore | **Solo el servidor** | Las reglas niegan escrituras de clientes. Los mensajes llevan `expireAt` (+7 días); los borra la política TTL de Firestore. |
| Fotos de perfil `*_profile_photos/{uid}.jpg` | Storage | La app | El servidor las borra al eliminar la cuenta. |
| Cuenta, login, claims `admin`/`superuser` | Authentication | Login: las apps. Claims y borrado: el servidor | El servidor verifica el ID token en cada request. |
| Notificaciones | Cloud Messaging | El servidor | Texto armado con `messages*.properties` en el idioma **del destinatario**. |

El idioma del destinatario sale de `drivers|passengers/{uid}.language` (lo
guardan las apps junto al `fcmToken`), nunca del request: el push casi siempre va
dirigido al *otro* participante.

---

## 4. Una petición, de punta a punta

`POST /api/rides/{passengerId}/accept` (un conductor acepta):

1. **`FirebaseTokenAuthenticationFilter`** verifica el ID token y deja un
   `AuthenticatedUser` en el contexto de seguridad (401 si falta o es inválido).
2. **`RideController.acceptRide`** toma la identidad con `@AuthenticationPrincipal`.
   El cliente nunca dice quién es.
3. **`RideLifecycleService.acceptRide`**:
   1. valida al conductor con `DriverRepository` (registrado, aprobado, no
      bloqueado → si no, 403);
   2. llama a `RideRepository.transact(passengerId, ride -> RideTransitions.assign(...))`.
4. **`RealtimeDbRideRepository.transact`** ejecuta la regla dentro de una
   transacción de Realtime Database (si dos conductores aceptan a la vez, gana
   uno) y devuelve el resultado.
5. El caso de uso traduce el resultado (409 si ya la tomó otro, 404 si no
   existe), avisa al pasajero con `UserNotifier` y publica `RideAccepted`.
6. **`ChatThreadOpener`** escucha el evento y abre el hilo de chat.
7. Cualquier excepción la convierte **`GlobalExceptionHandler`** en la respuesta
   HTTP; los controllers no tienen `try/catch`.

---

## 5. Errores

Todas las excepciones propias extienden `AppException` y declaran su `ErrorType`.
`GlobalExceptionHandler` es el único lugar que las traduce a HTTP, con cuerpo
`application/problem+json` (RFC 9457).

| `ErrorType` | HTTP | Ejemplo |
|---|---|---|
| `INVALID_INPUT` | 400 | Rol desconocido, motivo obligatorio ausente, mensaje vacío |
| *(autenticación)* | 401 | Sin token o token inválido |
| `FORBIDDEN` | 403 | No es el conductor asignado; jerarquía de roles |
| `NOT_FOUND` | 404 | No existe la carrera o el conductor |
| `CONFLICT` | 409 | Carrera ya tomada; carrera terminada; cuenta con viaje activo |
| `EXTERNAL_SERVICE` | 500 | Falló Firebase. El detalle va al log, no al cliente. |

## 6. Seguridad

Spring Security sin estado (sin sesión, cookies ni CSRF). Todo `/api/**` exige
un ID token de Firebase, **salvo `/api/public/**`** (hoy solo la consulta de
versión mínima, que las apps hacen antes de iniciar sesión). Fuera de `/api/**`
no se exige nada.

| Authority | De dónde sale | Para qué |
|---|---|---|
| `ROLE_ADMIN` | claim `admin` | Todo el panel (`/api/drivers`) |
| `ROLE_SUPERUSER` | claim `superuser` | Cambiar roles; actuar sobre admins |
| `ROLE_FOUNDER` | `taxiapp.admin.founder-uid` **y** `founder-email` coinciden con el token | Cambiar roles aunque no tenga el claim |

Los permisos que dependen de datos (quién puede aceptar una carrera, quién es
participante de un chat) no son roles del token: los decide el caso de uso.

## 7. Configuración

`application.properties` define los valores; se sobreescriben por perfil
(`application-{dev,prod}.properties`) o por variable de entorno.

| Propiedad | Variable de entorno | Qué es |
|---|---|---|
| `firebase.credentials.path` | `FIREBASE_CREDENTIALS_PATH` | Ruta al JSON de la cuenta de servicio |
| `firebase.credentials.json` | `FIREBASE_CREDENTIALS_JSON` | El JSON completo (tiene prioridad sobre la ruta) |
| `firebase.database.url` | `FIREBASE_DATABASE_URL` | URL de Realtime Database |
| `firebase.storage.bucket` | `FIREBASE_STORAGE_BUCKET` | Bucket de Storage (opcional) |
| `firebase.operation-timeout` | | Timeout de cada operación contra Firebase (15 s) |
| `taxiapp.rides.pending-request-expiry` | | Cuánto espera una solicitud sin conductor antes de cancelarse (35 s) |
| `taxiapp.rides.terminal-cleanup-delay` | | Cuánto tarda en borrarse una carrera terminada (10 s) |
| `taxiapp.chat.*` | | Largo máximo, largo del preview del push, retención (7 d) |
| `taxiapp.admin.founder-uid` / `founder-email` | `TAXIAPP_ADMIN_FOUNDER_UID` / `..._EMAIL` | La cuenta dueña del proyecto |
| `taxiapp.app-versions.{driver,passenger}.min-version` | `TAXIAPP_APPVERSIONS_DRIVER_MINVERSION`, `..._PASSENGER_...` | Versión mínima que admite el servidor (por defecto `0.0.0`: no bloquea a nadie) |
| `taxiapp.app-versions.{driver,passenger}.store-url` | | Dónde se descarga la app, para la pantalla de "actualiza" |

### Subir la versión mínima

Hacerlo **solo** cuando un cambio del servidor deje de ser compatible con las
apps viejas, y **después** de publicar la versión nueva en la tienda. No hace
falta publicar nada en las apps: se cambia la variable de entorno y se reinicia.
Las apps que no logran consultar (sin red, servidor caído) **no se bloquean**:
solo se corta el paso cuando el servidor dice explícitamente que la versión es
vieja.

Los secretos **no se versionan**: `secrets/` está en `.gitignore`. En local, el
fundador va en `secrets/taxiapp-secrets.properties`. El servidor tiene que
arrancar desde esta carpeta (`server/taxiapp`) porque las rutas por defecto son
relativas; si no encuentra la credencial, el error dice dónde buscó.

---

## 8. Tests

| Tipo | Dónde | Qué protege |
|---|---|---|
| **Caracterización** | `characterization/` | El comportamiento HTTP de los 14 endpoints. Hablan HTTP contra el servidor real con Firebase simulado en memoria (`FakeRealtimeDatabase` ejecuta las transacciones reales; `FakeFirestore`). Son la red de seguridad de cualquier refactor. |
| **Dominio / aplicación** | `<feature>/domain`, `<feature>/application` | Reglas puras y casos de uso con puertos en memoria. Sin Firebase ni Spring. |
| **Infra** | `<feature>/infra` | El mensaje FCM, el mapeo de documentos. |
| **Arquitectura** | `architecture/` | Las reglas de la sección 2. |

Los tests `bug_conocido_*` congelan comportamientos que **sabemos** que son
incorrectos; se actualizan solo cuando una fase los corrige a propósito.

```bash
# Hace falta JDK 21 (la terminal puede traer otro Java).
JAVA_HOME=<ruta-al-jdk-21> ./mvnw test
```

---

## 9. Cómo agregar algo

**Un endpoint a una feature existente**
1. Regla nueva → función pura en `domain` (con su test sin Firebase).
2. Caso de uso en `application`; si necesita un dato nuevo del almacenamiento,
   un método en el puerto del `domain`.
3. Implementarlo en `infra`.
4. Método en el controller de `api` (sin `try/catch`; lanzar excepciones de
   `common.error`).
5. Test de caracterización HTTP.

**Una feature nueva**
1. Paquete `com.areascript.taxiapp.<feature>` con `domain`, `application`,
   `infra`, `api` (solo las que hagan falta).
2. Agregarla a la lista `features` de `ArchitectureRulesTest`.
3. Si otra feature la necesita, exponer un puerto en su `domain`.

**Cambiar de base de datos** (ej. a Postgres): escribir una implementación nueva
del puerto en `infra` y cambiar cuál registra Spring. `domain`, `application` y
`api` no se tocan; los tests de caracterización dicen si algo cambió.

---

## 10. Límites conocidos

Cosas que **no están resueltas** y conviene tener presentes:

- **Las reglas de Realtime Database no están en el repo** (`firebase.json` solo
  tiene Firestore y Storage). Todas las transiciones de **estado** ya pasan por
  el servidor, pero las apps todavía escriben directo `taxi_requests/.../driver/location`
  (la ubicación en vivo), y las apps VIEJAS siguen escribiendo `driverArrived`
  y `tripStarted`. Mientras las reglas permitan esas escrituras, un cliente
  modificado podría saltarse al servidor. Cerrarlo exige versionar las reglas y
  subir la versión mínima de las apps (`taxiapp.app-versions.*`).
- **Los temporizadores viven en memoria.** Si el servidor se reinicia, una
  solicitud pendiente no vence ni una carrera terminada se limpia. Por eso el
  proceso debe estar siempre vivo (`deploy/taxiapp.service`).
- **`findInProgressOf` lee todo `taxi_requests`** y filtra en memoria;
  **`listAllDrivers` hace una lectura de vehículo por conductor**.
- **`DriverResponse` incluye el `fcmToken`** de cada conductor.
- **Las reglas de Firestore dejan leer `drivers/` y `passengers/` a cualquier
  usuario autenticado** (incluido teléfono y `fcmToken`).
- **El CI (`.github/workflows/deploy-qa.yml`) compila con `-DskipTests`** y usa
  JDK 26 mientras el proyecto es Java 21: los tests no corren antes de desplegar.
- **`bootstrap/ApprovalStatusBackfill`** es un script de una sola corrida que
  habla directo con Firestore; se puede borrar cuando ya se ejecutó en
  producción.
- **Postgres y el servidor comparten un único droplet de 1 vCPU / 2 GB.** Los
  límites de memoria de `compose.yaml` (db 640 MB, app 900 MB) existen para que
  uno no mate al otro. Sin alta disponibilidad: si el droplet cae, cae todo.
- **Los backups viven fuera del droplet solo si se corre `ops/pull-backups.ps1`.**
  El cron del droplet deja los dumps en el mismo disco; sin la copia a la PC, un
  fallo del droplet pierde datos y backups a la vez.

---

## 11. Base de datos (PostgreSQL + PostGIS)

Migración por pasos; el estado real hoy:

| Paso | Qué | Estado |
|---|---|---|
| 1 | Postgres/PostGIS en Docker + Flyway (esquema base) + backups | Hecho. Ninguna feature lee ni escribe en la base todavía. |
| 2a | Modelo `users` + `roles` + `drivers` / `passengers` / `vehicles` (V2 y V3), entidades JPA y copia desde Firestore | Hecho. Se llenan con el importador; nadie las lee todavía. |
| 2b | Endpoints de perfil en el servidor y apps escribiendo por ahí (Firestore deja de ser fuente de verdad) | Pendiente |
| 2c | `DriverRepository`, `DeviceRegistry`, etc. leen de Postgres | Pendiente |
| 3 | Despacho por cercanía con `ST_DWithin` | Pendiente |

La carrera en curso sigue en Realtime Database; el chat, en Firestore.

**Migraciones.** Flyway, en `src/main/resources/db/migration/V<n>__<descripcion>.sql`.
Una migración ya aplicada **no se edita nunca**: se agrega una nueva. Flyway
guarda el checksum de cada archivo y el arranque falla si cambió.

**Contenedores.** `compose.yaml` es el stack completo (db + app, tal como corre
en el droplet). `compose.dev.yaml` es un overlay que publica la base en
`127.0.0.1:5442` para correr el servidor desde IntelliJ:

```bash
docker compose -f compose.yaml -f compose.dev.yaml up -d db
```

La base nunca publica puertos en producción: Docker se salta `ufw`, así que un
`ports:` en `db` la expondría a internet aunque el firewall diga que no.

**Modelo de datos.**

```
roles(code PK)                       user | admin | superuser
users(id uuid PK, firebase_uid UNIQUE, role_code FK, email, nombres, bloqueo)
drivers(user_id PK/FK users)         teléfono, calificación, aprobación, token FCM, idioma, vehículo actual
passengers(user_id PK/FK users)      estado, token FCM, idioma
vehicles(vehicle_id PK, driver_id FK drivers)
```

- La identidad interna es `users.id`; `firebase_uid` es la externa. Si cambia el
  proveedor de autenticación, solo cambia esa columna.
- **Ser conductor no es un rol**: es tener fila en `drivers`. Una misma cuenta
  puede tener fila en `drivers` y en `passengers`.
- El token FCM y el idioma son **por instalación de app**, por eso viven en el
  perfil de cada una y no en `users`.
- Todo texto tiene un `CHECK` de longitud y todas las restricciones tienen nombre
  (`pk_`, `fk_`, `uq_`, `ck_`).

**Entidades JPA.** Son clases `*Row` (`UserRow`, `DriverRow`, `VehicleRow`,
`PassengerRow`) que viven en `infra` y no salen de ahí: el dominio sigue usando
sus records (`Driver`, `Vehicle`). Hibernate corre con `ddl-auto=validate`: si una
entidad no coincide con las tablas de Flyway, el servidor no arranca.

**Copiar los perfiles de Firestore a Postgres.** Una sola corrida, idempotente,
solo LEE Firestore (ver `bootstrap/FirestoreToPostgresImporter`):

```bash
java -jar taxiapp.jar --taxiapp.import.firestore-to-postgres=true
```

Se puede repetir; cada fila se sobrescribe con lo que haya en Firestore. No
borra. Mientras las apps sigan escribiendo perfiles en Firestore, Postgres se
desactualiza: por eso la copia es un paso previo, no la migración completa.

**Backups y restauración.** Ver [`ops/README.md`](ops/README.md).
