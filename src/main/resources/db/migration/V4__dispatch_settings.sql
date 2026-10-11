-- V4: configuración del despacho de carreras (qué conductores reciben un pedido
-- y cuándo), editable en tiempo de ejecución por un administrador.
--
-- Por qué en la base y no en application.properties: son valores que el
-- negocio quiere ajustar con la operación andando (ampliar el radio en hora
-- pico, esperar más por anillo...), sin un despliegue. Un panel de
-- administración los edita; el servidor los lee.
--
-- Modelo:
--   * dispatch_settings: UNA fila con los parámetros globales.
--   * dispatch_rings: una fila por ANILLO de búsqueda. El pedido se ofrece
--       primero a los conductores del anillo 1; si nadie acepta en
--       `wait_seconds`, se amplía al anillo 2 (solo a los nuevos), y así hasta
--       el último. Si nadie acepta, el pasajero recibe "no hay vehículos".
--       Como son filas, el número de intentos es simplemente cuántas filas hay.
--
-- Las reglas entre filas (radios estrictamente crecientes, números de anillo
-- consecutivos) no se pueden expresar con un CHECK: las valida el dominio al
-- guardar. La base garantiza lo que sí puede: rangos, unicidad y fila única.

CREATE TABLE dispatch_settings (
    -- Siempre 1: el CHECK impide una segunda fila. Es una fila de configuración,
    -- no una colección.
    id                           integer     NOT NULL DEFAULT 1,

    -- Un conductor con una ubicación más vieja que esto no recibe pedidos
    -- (cerró la app, perdió señal...). Ver el filtro de frescura del despacho.
    location_max_age_seconds     integer     NOT NULL DEFAULT 45,

    -- Parámetros que la app del conductor leerá del servidor para no tener que
    -- publicar una versión nueva al ajustarlos: cada cuántos segundos reporta
    -- su ubicación disponible, y cuánto debe moverse para reportar antes.
    driver_ping_interval_seconds integer     NOT NULL DEFAULT 10,
    driver_ping_min_move_meters  integer     NOT NULL DEFAULT 30,

    updated_at                   timestamptz NOT NULL DEFAULT now(),
    -- Quién cambió la configuración por última vez (NULL = valores iniciales).
    updated_by                   uuid,

    CONSTRAINT pk_dispatch_settings PRIMARY KEY (id),
    CONSTRAINT ck_dispatch_settings_single_row CHECK (id = 1),
    CONSTRAINT fk_dispatch_settings_updated_by
        FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_dispatch_settings_location_max_age
        CHECK (location_max_age_seconds BETWEEN 5 AND 600),
    CONSTRAINT ck_dispatch_settings_ping_interval
        CHECK (driver_ping_interval_seconds BETWEEN 2 AND 120),
    CONSTRAINT ck_dispatch_settings_ping_min_move
        CHECK (driver_ping_min_move_meters BETWEEN 0 AND 1000)
);

INSERT INTO dispatch_settings (id) VALUES (1);

CREATE TABLE dispatch_rings (
    -- Orden de búsqueda: 1 es el primero (el más cercano).
    ring_number   integer     NOT NULL,
    -- Radio en METROS desde el punto de recogida, medido en línea recta.
    radius_meters integer     NOT NULL,
    -- Cuánto se espera a que alguien de este anillo acepte antes de ampliar.
    wait_seconds  integer     NOT NULL DEFAULT 10,
    -- Tope de conductores a quienes se ofrece en este anillo (los más cercanos),
    -- para no inundar de avisos.
    max_drivers   integer     NOT NULL DEFAULT 10,

    updated_at    timestamptz NOT NULL DEFAULT now(),
    updated_by    uuid,

    CONSTRAINT pk_dispatch_rings PRIMARY KEY (ring_number),
    CONSTRAINT uq_dispatch_rings_radius UNIQUE (radius_meters),
    CONSTRAINT fk_dispatch_rings_updated_by
        FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_dispatch_rings_ring_number CHECK (ring_number >= 1),
    CONSTRAINT ck_dispatch_rings_radius      CHECK (radius_meters BETWEEN 100 AND 50000),
    CONSTRAINT ck_dispatch_rings_wait        CHECK (wait_seconds BETWEEN 1 AND 120),
    CONSTRAINT ck_dispatch_rings_max_drivers CHECK (max_drivers BETWEEN 1 AND 100)
);

-- Valores iniciales: 3 anillos de 3 km, 6 km y 9 km, 10 s cada uno (30 s en
-- total, el mismo tiempo que el diálogo "Buscando conductor" del pasajero).
INSERT INTO dispatch_rings (ring_number, radius_meters, wait_seconds, max_drivers) VALUES
    (1, 3000, 10, 10),
    (2, 6000, 10, 10),
    (3, 9000, 10, 10);
