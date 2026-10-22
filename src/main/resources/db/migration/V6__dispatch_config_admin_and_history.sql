-- V6: edición de la configuración del despacho desde la app de administración.
--
-- Tres cosas:
--
--   1. dispatch_settings.admins_can_edit: interruptor que SOLO el superuser
--      toca. Apagado (por defecto), solo el superuser puede editar anillos y
--      frescura de ubicación; encendido, los admins también. Ver es siempre
--      posible para un admin. Vive en la misma fila única de configuración
--      porque es otro parámetro global del despacho.
--
--   2. dispatch_settings.version: contador de la configuración. Cada guardado
--      lo incrementa, y quien edita manda la versión que vio: si alguien guardó
--      antes, el segundo recibe un conflicto en vez de pisarlo en silencio. Es un
--      contador y no updated_at porque un timestamp pierde precisión al viajar
--      por JSON entre el servidor y la app.
--
--   3. Historial: quién cambió qué y cuándo. Cada cambio guarda la FOTO completa
--      de la configuración antes y después (no un diff): una foto se lee sin
--      reconstruir nada y no depende de cómo se numeren los anillos. Los anillos
--      de cada foto son filas (igual que dispatch_rings), no un JSON.

ALTER TABLE dispatch_settings
    ADD COLUMN admins_can_edit boolean NOT NULL DEFAULT false,
    ADD COLUMN version         bigint  NOT NULL DEFAULT 0;

CREATE TABLE dispatch_config_changes (
    id          bigint      GENERATED ALWAYS AS IDENTITY,
    changed_at  timestamptz NOT NULL DEFAULT now(),
    -- NULL si la persona se borró después: el cambio queda en el historial.
    changed_by  uuid,
    -- 'config': se editaron anillos o frescura. 'admins_can_edit': el
    -- superuser movió el interruptor.
    change_type text        NOT NULL,

    before_location_max_age_seconds integer NOT NULL,
    before_admins_can_edit          boolean NOT NULL,
    after_location_max_age_seconds  integer NOT NULL,
    after_admins_can_edit           boolean NOT NULL,

    CONSTRAINT pk_dispatch_config_changes PRIMARY KEY (id),
    CONSTRAINT fk_dispatch_config_changes_changed_by
        FOREIGN KEY (changed_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_dispatch_config_changes_type
        CHECK (change_type IN ('config', 'admins_can_edit'))
);

CREATE TABLE dispatch_config_change_rings (
    change_id     bigint  NOT NULL,
    -- A qué foto pertenece el anillo: la de antes o la de después del cambio.
    side          text    NOT NULL,
    ring_number   integer NOT NULL,
    radius_meters integer NOT NULL,
    wait_seconds  integer NOT NULL,
    max_drivers   integer NOT NULL,

    CONSTRAINT pk_dispatch_config_change_rings PRIMARY KEY (change_id, side, ring_number),
    CONSTRAINT fk_dispatch_config_change_rings_change
        FOREIGN KEY (change_id) REFERENCES dispatch_config_changes (id) ON DELETE CASCADE,
    CONSTRAINT ck_dispatch_config_change_rings_side CHECK (side IN ('before', 'after'))
);
