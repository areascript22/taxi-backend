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
| `user` | Quién es la persona (identidad y rol guardados en Postgres) y sus perfiles de conductor y pasajero | `GET /api/me`<br>`PUT /api/me/driver-profile`<br>`PUT /api/me/passenger-profile`<br>`PUT /api/me/device` |
| `dispatch` | Presencia de los conductores (en línea, ocupado, dónde están) y, más adelante, a quién se le ofrece un pedido | `PUT /api/me/driver/availability`<br>`PUT /api/me/driver/location` |
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
| Persona `users`, perfiles `drivers` / `passengers` y vehículo `vehicles` | **Postgres** | Solo el **servidor** (`PUT /api/me/driver-profile`, `/passenger-profile`, `/device` y el panel de administración) | Las apps no escriben perfiles: mandan sus datos al servidor, que valida y decide aprobación, rol y bloqueo. |
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

**El token prueba QUIÉN es; la base dice QUÉ PUEDE hacer.** En cada request el
filtro verifica el ID token y busca a la persona por `firebase_uid` en `users`
(si es su primer request, la crea con rol `user`). Las authorities salen de
`users.role_code`, no de los claims. Por eso un cambio de rol vale en el
siguiente request y no cuando el token se renueve (hasta 1 hora), y un claim que
la base no respalda no abre nada.

| Authority | De dónde sale | Para qué |
|---|---|---|
| `ROLE_ADMIN` | `users.role_code` = `admin` o `superuser` | Todo el panel (`/api/drivers`) |
| `ROLE_SUPERUSER` | `users.role_code` = `superuser` | Cambiar roles; actuar sobre admins |
| `ROLE_FOUNDER` | `taxiapp.admin.founder-uid` **y** `founder-email` coinciden con el token | Cambiar roles aunque no tenga el rol |

- **Falla cerrado:** si la base no responde, el filtro contesta 503 en vez de
  autorizar con lo que trae el token.
- **Claims en transición:** siguen sincronizándose (las reglas de Firestore y
  Storage los leen). Si difieren de la base el servidor lo avisa en el log
  (`Rol desalineado`) y manda la base.
- **El rol se escribe en tres lugares** mientras dure la transición: claims
  (`IdentityProvider`), Firestore y Postgres (`UserAccounts.setRole`). Si el
  último falla, repetir la operación lo corrige (es idempotente).
- **Borrar una cuenta** borra también la fila de `users` (y en cascada sus
  perfiles). Una petición rezagada con un token todavía válido puede volver a
  crear una fila vacía; es inofensiva.

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
- **(Hasta cerrar el paso final de la migración de perfiles) Las reglas de
  Firestore dejan leer `drivers/` y `passengers/` a cualquier
  usuario autenticado** (incluido teléfono y `fcmToken`).
- **El CI (`.github/workflows/deploy-qa.yml`) compila con `-DskipTests`** y usa
  JDK 26 mientras el proyecto es Java 21: los tests no corren antes de desplegar.
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
| 2a | Modelo `users` + `roles` + `drivers` / `passengers` / `vehicles` (V2 y V3) y entidades JPA | Hecho |
| 2b | Endpoints de perfil en el servidor (`driver-profile`, `passenger-profile`) | Hecho en el servidor. Las apps todavía escriben en Firestore. |
| 2c | `DriverRepository` y `DeviceRegistry` leen y escriben en Postgres; sin importador | Hecho |
| 3a | Presencia de conductores y ubicación en PostGIS (V5): interruptor en línea, ubicación, ocupado/libre derivado de la carrera, consulta por radio | Hecho. Falta el despacho por anillos que la use. |
| 3b | Despacho por anillos y bandeja de ofertas por conductor (`driver_offers/{uid}`) | Hecho en el servidor. Las apps todavía no escuchan la bandeja; `enforce-offers` sigue apagado. |
| 3c | Apps: ubicación por HTTP y escuchar la bandeja en vez de `taxi_requests`; subir versión mínima; `enforce-offers=true`; cerrar reglas | Pendiente |
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

**Presencia de los conductores (V5).** Lo que el despacho consulta para saber a
quién ofrecerle un pedido:

```
drivers.is_online      lo pone el CONDUCTOR (interruptor de la app)
drivers.on_ride        lo pone el SERVIDOR: al aceptar (RideAccepted) y al terminar (RideEnded)
driver_locations       última posición, UNA fila por conductor, geography(Point,4326) + índice GiST
```

"Disponible" = en línea **y** no en carrera **y** ubicación reciente
(`dispatch_settings.location_max_age_seconds`). El cliente nunca dice "estoy
libre": ocupado/libre lo deriva el servidor del ciclo de la carrera.

**Un solo endpoint de ubicación, dos destinos** (`PUT /api/me/driver/location`).
La app no cambia de endpoint; el servidor decide:

| Situación | Destino | Por qué |
|---|---|---|
| Sin carrera (`passengerId` ausente) | Postgres `driver_locations` | El despacho busca al más cercano |
| En carrera (`passengerId`) | Nodo de la carrera en RTDB (`driver/location`) | Lo lee el mapa del pasajero; ocupado no recibe pedidos, así que no se escribe en Postgres |

La escritura al nodo pasa por `RideTransitions.updateDriverLocation` (una regla
atómica): si la carrera ya no existe no se escribe nada, así no aparece un nodo
fantasma que los conductores verían como una solicitud.

**Marcas colgadas.** Si se pierde un evento (el servidor se reinició a mitad),
`on_ride` puede quedar en `true` sin carrera. Se corrige solo: al ponerse en
línea y al reportar ubicación se confirma contra la carrera real (una sola vez,
solo en ese caso raro). Quien deja de reportar sale del despacho por la
frescura, sin detectar "desconexiones".

**Límite conocido de esta etapa.** La aprobación y el bloqueo todavía viven en
Firestore y no se reflejan en Postgres. Se exigen al ponerse en línea y al
aceptar, pero `findAvailableNear` aún no los filtra: un conductor bloqueado
estando en línea podría recibir una oferta (el accept lo rechaza). Cuando
`DriverRepository` pase a Postgres se agrega el filtro.

**El despacho por anillos (`dispatch/application/RingDispatcher`).** Cuando un
pasajero pide un taxi:

```
RideRequested ─▶ anillo 1: los N disponibles más cercanos (≤ radio) ─▶ driver_offers/{uid}/{passengerId}
                 espera wait_seconds
              ─▶ anillo 2: solo los NUEVOS dentro del radio mayor ─▶ ...
              ─▶ sin anillos ─▶ NoDriversAvailable
RideAccepted / RideRequestWithdrawn ─▶ se retiran todas las ofertas y la búsqueda termina
```

- **Reacciona a hechos, no manda.** Escucha `RideRequested`, `RideAccepted` y
  `RideRequestWithdrawn`, y le devuelve a `ride` un solo hecho
  (`NoDriversAvailable`); quien cierra la solicitud es `ride`.
- **Cada búsqueda lleva una foto del plan** (`DispatchPlan`): un cambio de
  configuración a mitad de una búsqueda no la altera.
- **La bandeja es la evidencia.** "A quién se le ofreció" se verifica contra
  `driver_offers/{uid}/{passengerId}` (con el `rideId`, para que una oferta vieja
  no autorice una solicitud nueva), no contra la memoria del servidor.
- **Dos interruptores** (`taxiapp.dispatch.*`), ambos activos por defecto:
  `enabled` (busca y ofrece) y `enforce-offers` (solo acepta quien recibió la
  oferta, y "no hay vehículos" al agotar los anillos). `enforce-offers` es un
  interruptor y no una constante por si alguna vez hay que convivir con una app
  que todavía lea todo `taxi_requests`; mientras la app no esté publicada no es
  el caso.
- **Reglas de RTDB** (`firebase/database.rules.json`): `driver_offers/$uid` solo
  lo lee su dueño y `taxi_requests/$passengerId` solo el pasajero y el conductor
  asignado; nadie escribe desde el cliente. **Hay que desplegarlas**
  (`firebase deploy --only database`): hasta entonces las reglas vigentes son
  las que tenga el proyecto.
- **Límite:** los temporizadores de los anillos viven en memoria. Si el servidor
  se reinicia a mitad de una búsqueda, esa búsqueda no sigue ampliando; la
  solicitud igual vence (mismo límite que el vencimiento de solicitudes).
- **Sin push de respaldo todavía:** una app cerrada se entera por el servicio en
  primer plano (que escuchará la bandeja). Un FCM de alta prioridad como
  respaldo queda pendiente.

**Configuración del despacho (V4).** Lo que decide a quién se le ofrece un pedido
vive en la base y lo edita un administrador, sin desplegar:

```
dispatch_settings(id = 1)   frescura de la ubicación, intervalo y umbral de movimiento del ping
dispatch_rings(ring_number) radio (m), espera (s) y máximo de conductores por anillo
```

Los anillos son FILAS: agregar un intento es agregar una fila. La base garantiza
rangos, unicidad y fila única; lo que no cabe en un `CHECK` (radios
estrictamente crecientes, números consecutivos) lo valida el dominio al guardar.
Cuando se implemente el despacho, cada carrera debe guardar una COPIA de los
anillos con los que arrancó, para que un cambio de configuración a mitad de una
búsqueda no la altere.

**Dónde vive el código de las personas.** Toda la persistencia de personas
(`UserRow`, `DriverRow`, `VehicleRow`, `PassengerRow` y sus repositorios) está en
`user/infra`. Las demás features piden lo que necesitan por un puerto de su
dominio y es `user/infra` quien lo implementa (hoy `UserAccounts` y
`UserProfiles`; en el siguiente paso, también `DriverRepository`). Así ninguna
feature toca las tablas de otra.

`taxiapp.persistence.postgres=false` desactiva esos adaptadores; solo lo usan
los tests de caracterización, que ponen una base de usuarios en memoria.

**Dispositivo (`PUT /api/me/device`).** El token de notificaciones y el idioma son
por INSTALACIÓN, no por persona: quien usa las dos apps con la misma cuenta
tiene uno por app. El body lleva `app` (`driver`/`passenger`). Durante la
transición se guarda en Postgres **y** en Firestore, porque las notificaciones
todavía leen de Firestore y las apps viejas siguen escribiendo ahí.

**Entidades JPA.** Son clases `*Row` (`UserRow`, `DriverRow`, `VehicleRow`,
`PassengerRow`, `DispatchSettingsRow`, `DispatchRingRow`) que viven en `infra` y no salen de ahí: el dominio sigue usando
sus records (`Driver`, `Vehicle`). Hibernate corre con `ddl-auto=validate`: si una
entidad no coincide con las tablas de Flyway, el servidor no arranca.

**Alta y edición de perfiles.** El único camino para escribir un perfil es el
servidor (`PUT /api/me/driver-profile` y `PUT /api/me/passenger-profile`).
Firestore ya no guarda perfiles: solo conserva los chats.

- **Idempotente (PUT):** crear y editar son la misma llamada; un doble toque o un
  reintento por timeout no duplica nada.
- **Nace `pending`:** el cliente no decide aprobación, rol, bloqueo ni
  calificación (el request ni siquiera los modela).
- **Foto:** la sube la app a Storage (reglas por uid) y manda la URL. El
  servidor no recibe el archivo, así que solo acepta la URL que apunta a SU
  objeto (`ProfilePhotoUrl`: host, bucket, carpeta y uid exactos).
- **Vehículo:** cambiar la placa o la matrícula devuelve la verificación del
  vehículo a `pending`; cambiar el color, por ejemplo, no.
- **Validación:** los mismos límites que los `CHECK` de la base, para responder
  400 con un mensaje útil y no un 500.

**Backups y restauración.** Ver [`ops/README.md`](ops/README.md).
