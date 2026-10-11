-- Perfiles de usuario: pasajeros, conductores y vehículos.
--
-- Hasta ahora viven en Firestore (colecciones passengers, drivers, vehicles).
-- Estas tablas reciben su copia (ver FirestoreToPostgresImporter) y, en el paso
-- siguiente de la migración, pasan a ser la fuente de verdad.
--
-- Convenciones:
--   * El id es el uid de Firebase Auth (text), no un número: es la identidad
--     que ya viene en el token, y así la copia desde Firestore conserva los ids.
--   * Los textos que la app trata como "" cuando faltan son NOT NULL DEFAULT '';
--     los que son opcionales de verdad (foto, motivo de bloqueo...) admiten NULL.
--   * Los valores de dominio (role, approval_status) se guardan como el texto
--     que ya usan la API y las apps, con un CHECK que impide valores inválidos.
--   * Todas las fechas son timestamptz (UTC).

CREATE TABLE passengers (
    uid         text PRIMARY KEY,
    first_name  text NOT NULL DEFAULT '',
    last_name   text NOT NULL DEFAULT '',
    email       text NOT NULL DEFAULT '',
    photo_url   text,
    status      text NOT NULL DEFAULT 'active',
    -- Para las notificaciones push: el copy lo arma el servidor en el idioma
    -- del DESTINATARIO, por eso viven junto al perfil.
    language    text,
    fcm_token   text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE drivers (
    uid              text PRIMARY KEY,
    first_name       text NOT NULL DEFAULT '',
    last_name        text NOT NULL DEFAULT '',
    email            text NOT NULL DEFAULT '',
    phone_number     text NOT NULL DEFAULT '',
    photo_url        text,
    rating           double precision NOT NULL DEFAULT 5
                         CHECK (rating >= 0 AND rating <= 5),

    role             text NOT NULL DEFAULT 'driver'
                         CHECK (role IN ('driver', 'admin', 'superuser')),

    approval_status  text NOT NULL DEFAULT 'pending'
                         CHECK (approval_status IN ('pending', 'approved', 'rejected')),
    approved_at      timestamptz,
    approved_by      text,
    rejection_reason text,
    rejected_at      timestamptz,
    rejected_by      text,

    is_blocked       boolean NOT NULL DEFAULT false,
    block_reason     text,
    blocked_at       timestamptz,
    blocked_by       text,

    language         text,
    fcm_token        text,

    -- Vehículo con el que trabaja hoy. La clave foránea se agrega abajo,
    -- porque vehicles también apunta a drivers.
    vehicle_id       text,

    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE vehicles (
    vehicle_id          text PRIMARY KEY,
    driver_uid          text NOT NULL REFERENCES drivers (uid) ON DELETE CASCADE,
    plate               text NOT NULL DEFAULT '',
    brand               text NOT NULL DEFAULT '',
    model               text NOT NULL DEFAULT '',
    year                integer NOT NULL DEFAULT 0,
    color               text NOT NULL DEFAULT '',
    registration_number text NOT NULL DEFAULT '',
    verification_status text NOT NULL DEFAULT 'pending',
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);

-- La placa NO es única a propósito: los datos viejos de Firestore pueden
-- repetirla, y la regla de negocio (si debe serlo) se decide con datos reales.

ALTER TABLE drivers
    ADD CONSTRAINT fk_drivers_vehicle
    FOREIGN KEY (vehicle_id) REFERENCES vehicles (vehicle_id) ON DELETE SET NULL;

CREATE INDEX idx_vehicles_driver_uid ON vehicles (driver_uid);

-- El panel de administración filtra por estado de aprobación.
CREATE INDEX idx_drivers_approval_status ON drivers (approval_status);
