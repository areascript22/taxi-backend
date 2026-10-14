-- V3: identidad propia (users), roles como tabla y perfiles de conductor y
-- pasajero colgando de users.
--
-- Qué cambia respecto a V2:
--   * users: una fila por persona (id propio uuid + firebase_uid UNIQUE).
--   * roles: tabla de consulta; users.role_code la referencia.
--       El rol 'driver' desaparece: ser conductor = tener fila en drivers.
--       Los que eran 'driver' pasan a 'user'.
--   * drivers / passengers: su PK es user_id (FK a users). Conservan lo que es
--       propio de cada app: token FCM, idioma y foto.
--   * Todo texto lleva un CHECK de longitud, y todas las restricciones tienen
--       nombre explícito (pk_, fk_, uq_, ck_).
--
-- Flyway ejecuta esta migración dentro de UNA transacción: o se aplica completa
-- o no se aplica nada.

-- ---------------------------------------------------------------------------
-- 0. Apartar las tablas de V2. Se copian al final y se borran.
--    Las restricciones de las tablas nuevas tienen nombres distintos a los
--    automáticos de V2 (drivers_pkey, etc.), así no chocan.
-- ---------------------------------------------------------------------------
ALTER TABLE vehicles   RENAME TO vehicles_old;
ALTER TABLE drivers    RENAME TO drivers_old;
ALTER TABLE passengers RENAME TO passengers_old;

-- ---------------------------------------------------------------------------
-- 1. Roles
-- ---------------------------------------------------------------------------
CREATE TABLE roles (
                       code        text NOT NULL,
                       description text NOT NULL,
                       CONSTRAINT pk_roles PRIMARY KEY (code),
                       CONSTRAINT ck_roles_code_length CHECK (char_length(code) BETWEEN 1 AND 30)
);

INSERT INTO roles (code, description) VALUES
                                          ('user',      'Usuario corriente: pasajero y/o conductor'),
                                          ('admin',     'Administra conductores: aprobar, rechazar y bloquear'),
                                          ('superuser', 'Todo lo de admin, más gestionar a los admins');

-- ---------------------------------------------------------------------------
-- 2. Users: la identidad
-- ---------------------------------------------------------------------------
CREATE TABLE users (
                       id           uuid        NOT NULL DEFAULT gen_random_uuid(),
    -- La identidad EXTERNA (Firebase Auth). Si algún día cambia el proveedor,
    -- solo cambia esta columna: el resto de tablas apunta a users.id.
                       firebase_uid text        NOT NULL,
                       email        text        NOT NULL DEFAULT '',
                       first_name   text        NOT NULL DEFAULT '',
                       last_name    text        NOT NULL DEFAULT '',
                       role_code    text        NOT NULL DEFAULT 'user',

    -- El bloqueo es de la CUENTA, no del conductor: así se puede bloquear
    -- también a un pasajero.
                       is_blocked   boolean     NOT NULL DEFAULT false,
                       block_reason text,
                       blocked_at   timestamptz,
                       blocked_by   uuid,

                       created_at   timestamptz NOT NULL DEFAULT now(),
                       updated_at   timestamptz NOT NULL DEFAULT now(),

                       CONSTRAINT pk_users PRIMARY KEY (id),
                       CONSTRAINT uq_users_firebase_uid UNIQUE (firebase_uid),
                       CONSTRAINT fk_users_role FOREIGN KEY (role_code) REFERENCES roles (code),
                       CONSTRAINT fk_users_blocked_by FOREIGN KEY (blocked_by) REFERENCES users (id) ON DELETE SET NULL,
                       CONSTRAINT ck_users_firebase_uid_length CHECK (char_length(firebase_uid) BETWEEN 1 AND 128),
                       CONSTRAINT ck_users_email_length        CHECK (char_length(email) <= 254),
                       CONSTRAINT ck_users_first_name_length   CHECK (char_length(first_name) <= 100),
                       CONSTRAINT ck_users_last_name_length    CHECK (char_length(last_name) <= 100),
                       CONSTRAINT ck_users_block_reason_length CHECK (block_reason IS NULL OR char_length(block_reason) <= 500)
);

-- Conductores (sus datos ganan si la misma persona también es pasajero).
INSERT INTO users (firebase_uid, email, first_name, last_name, role_code,
                   is_blocked, block_reason, blocked_at, created_at, updated_at)
SELECT d.uid, d.email, d.first_name, d.last_name,
       CASE d.role WHEN 'driver' THEN 'user' ELSE d.role END,
       d.is_blocked, d.block_reason, d.blocked_at, d.created_at, d.updated_at
FROM drivers_old d;

-- Pasajeros que no son también conductores.
INSERT INTO users (firebase_uid, email, first_name, last_name, created_at, updated_at)
SELECT p.uid, p.email, p.first_name, p.last_name, p.created_at, p.updated_at
FROM passengers_old p
WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.firebase_uid = p.uid);

-- blocked_by guardaba el uid de Firebase del admin: pasa a ser su users.id.
-- Si ese admin ya no existe queda en NULL.
UPDATE users u
SET blocked_by = actor.id
    FROM drivers_old d
JOIN users actor ON actor.firebase_uid = d.blocked_by
WHERE u.firebase_uid = d.uid;

-- ---------------------------------------------------------------------------
-- 3. Drivers: lo propio del conductor
-- ---------------------------------------------------------------------------
CREATE TABLE drivers (
                         user_id          uuid             NOT NULL,
                         phone_number     text             NOT NULL DEFAULT '',
                         photo_url        text,
                         rating           double precision NOT NULL DEFAULT 5,

                         approval_status  text             NOT NULL DEFAULT 'pending',
                         approved_at      timestamptz,
                         approved_by      uuid,
                         rejection_reason text,
                         rejected_at      timestamptz,
                         rejected_by      uuid,

    -- Por instalación de la app del conductor.
                         language         text,
                         fcm_token        text,

    -- Vehículo con el que trabaja hoy. Su FK se agrega después de crear vehicles.
                         vehicle_id       text,

                         created_at       timestamptz      NOT NULL DEFAULT now(),
                         updated_at       timestamptz      NOT NULL DEFAULT now(),

                         CONSTRAINT pk_drivers PRIMARY KEY (user_id),
                         CONSTRAINT fk_drivers_user        FOREIGN KEY (user_id)     REFERENCES users (id) ON DELETE CASCADE,
                         CONSTRAINT fk_drivers_approved_by FOREIGN KEY (approved_by) REFERENCES users (id) ON DELETE SET NULL,
                         CONSTRAINT fk_drivers_rejected_by FOREIGN KEY (rejected_by) REFERENCES users (id) ON DELETE SET NULL,
                         CONSTRAINT ck_drivers_rating CHECK (rating >= 0 AND rating <= 5),
                         CONSTRAINT ck_drivers_approval_status CHECK (approval_status IN ('pending', 'approved', 'rejected')),
                         CONSTRAINT ck_drivers_phone_length  CHECK (char_length(phone_number) <= 20),
                         CONSTRAINT ck_drivers_photo_length  CHECK (photo_url IS NULL OR char_length(photo_url) <= 2048),
                         CONSTRAINT ck_drivers_rejection_reason_length CHECK (rejection_reason IS NULL OR char_length(rejection_reason) <= 500),
                         CONSTRAINT ck_drivers_language_length  CHECK (language IS NULL OR char_length(language) <= 10),
    CONSTRAINT ck_drivers_fcm_token_length CHECK (fcm_token IS NULL OR char_length(fcm_token) <= 1024)
);

INSERT INTO drivers (user_id, phone_number, photo_url, rating, approval_status,
                     approved_at, rejection_reason, rejected_at, language, fcm_token,
                     created_at, updated_at)
SELECT u.id, d.phone_number, d.photo_url, d.rating, d.approval_status,
       d.approved_at, d.rejection_reason, d.rejected_at, d.language, d.fcm_token,
       d.created_at, d.updated_at
FROM drivers_old d
         JOIN users u ON u.firebase_uid = d.uid;

-- approved_by / rejected_by: de uid de Firebase (texto) a users.id.
UPDATE drivers dr
SET approved_by = actor.id
    FROM drivers_old d
JOIN users me    ON me.firebase_uid = d.uid
    JOIN users actor ON actor.firebase_uid = d.approved_by
WHERE dr.user_id = me.id;

UPDATE drivers dr
SET rejected_by = actor.id
    FROM drivers_old d
JOIN users me    ON me.firebase_uid = d.uid
    JOIN users actor ON actor.firebase_uid = d.rejected_by
WHERE dr.user_id = me.id;

-- ---------------------------------------------------------------------------
-- 4. Vehicles
--    El id sigue siendo el texto que generó Firestore: las apps y el importador
--    todavía lo usan tal cual. Cambiarlo a uuid se puede hacer más adelante.
-- ---------------------------------------------------------------------------
CREATE TABLE vehicles (
                          vehicle_id          text        NOT NULL,
                          driver_id           uuid        NOT NULL,
                          plate               text        NOT NULL DEFAULT '',
                          brand               text        NOT NULL DEFAULT '',
                          model               text        NOT NULL DEFAULT '',
                          year                integer     NOT NULL DEFAULT 0,
                          color               text        NOT NULL DEFAULT '',
                          registration_number text        NOT NULL DEFAULT '',
                          verification_status text        NOT NULL DEFAULT 'pending',
                          created_at          timestamptz NOT NULL DEFAULT now(),
                          updated_at          timestamptz NOT NULL DEFAULT now(),

                          CONSTRAINT pk_vehicles PRIMARY KEY (vehicle_id),
                          CONSTRAINT fk_vehicles_driver FOREIGN KEY (driver_id) REFERENCES drivers (user_id) ON DELETE CASCADE,
                          CONSTRAINT ck_vehicles_id_length           CHECK (char_length(vehicle_id) BETWEEN 1 AND 64),
                          CONSTRAINT ck_vehicles_plate_length        CHECK (char_length(plate) <= 15),
                          CONSTRAINT ck_vehicles_brand_length        CHECK (char_length(brand) <= 50),
                          CONSTRAINT ck_vehicles_model_length        CHECK (char_length(model) <= 50),
                          CONSTRAINT ck_vehicles_color_length        CHECK (char_length(color) <= 30),
                          CONSTRAINT ck_vehicles_registration_length CHECK (char_length(registration_number) <= 30),
                          CONSTRAINT ck_vehicles_verification_length CHECK (char_length(verification_status) <= 20),
                          CONSTRAINT ck_vehicles_year                CHECK (year = 0 OR year BETWEEN 1950 AND 2100)
    );

INSERT INTO vehicles (vehicle_id, driver_id, plate, brand, model, year, color,
                      registration_number, verification_status, created_at, updated_at)
SELECT v.vehicle_id, d.user_id, v.plate, v.brand, v.model, v.year, v.color,
       v.registration_number, v.verification_status, v.created_at, v.updated_at
FROM vehicles_old v
         JOIN users u   ON u.firebase_uid = v.driver_uid
         JOIN drivers d ON d.user_id = u.id;

-- Ahora que vehicles existe: el vehículo actual de cada conductor.
ALTER TABLE drivers
    ADD CONSTRAINT fk_drivers_current_vehicle
        FOREIGN KEY (vehicle_id) REFERENCES vehicles (vehicle_id) ON DELETE SET NULL;

UPDATE drivers dr
SET vehicle_id = o.vehicle_id
    FROM drivers_old o
JOIN users u ON u.firebase_uid = o.uid
WHERE dr.user_id = u.id
  AND o.vehicle_id IN (SELECT vehicle_id FROM vehicles);

-- ---------------------------------------------------------------------------
-- 5. Passengers: lo propio del pasajero
-- ---------------------------------------------------------------------------
CREATE TABLE passengers (
                            user_id    uuid        NOT NULL,
                            photo_url  text,
                            status     text        NOT NULL DEFAULT 'active',
    -- Por instalación de la app del pasajero.
                            language   text,
                            fcm_token  text,
                            created_at timestamptz NOT NULL DEFAULT now(),
                            updated_at timestamptz NOT NULL DEFAULT now(),

                            CONSTRAINT pk_passengers PRIMARY KEY (user_id),
                            CONSTRAINT fk_passengers_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
                            CONSTRAINT ck_passengers_photo_length     CHECK (photo_url IS NULL OR char_length(photo_url) <= 2048),
                            CONSTRAINT ck_passengers_status_length    CHECK (char_length(status) <= 20),
                            CONSTRAINT ck_passengers_language_length  CHECK (language IS NULL OR char_length(language) <= 10),
    CONSTRAINT ck_passengers_fcm_token_length CHECK (fcm_token IS NULL OR char_length(fcm_token) <= 1024)
);

INSERT INTO passengers (user_id, photo_url, status, language, fcm_token, created_at, updated_at)
SELECT u.id, p.photo_url, p.status, p.language, p.fcm_token, p.created_at, p.updated_at
FROM passengers_old p
         JOIN users u ON u.firebase_uid = p.uid;

-- ---------------------------------------------------------------------------
-- 6. Limpieza: borrar las tablas de V2 y crear los índices
--    (se crean al final porque idx_drivers_approval_status existía en V2).
-- ---------------------------------------------------------------------------
-- Una sola sentencia: drivers_old y vehicles_old se referencian entre sí.
DROP TABLE vehicles_old, drivers_old, passengers_old;

CREATE INDEX idx_drivers_approval_status ON drivers (approval_status);
CREATE INDEX idx_vehicles_driver_id      ON vehicles (driver_id);