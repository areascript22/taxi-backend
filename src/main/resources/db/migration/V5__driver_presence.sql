-- V5: presencia de los conductores: si están en línea, si están en una carrera y
-- dónde están. Es lo que el despacho por cercanía consulta para decidir a quién
-- se le ofrece un pedido.
--
-- Qué guarda cada cosa y por qué ahí:
--
--   drivers.is_online  Lo pone el CONDUCTOR con el interruptor de la app.
--   drivers.on_ride    Lo pone el SERVIDOR: se activa al aceptar una carrera y se
--                      apaga al terminarla o cancelarla. El cliente no puede
--                      decir "estoy libre" estando en una carrera.
--   driver_locations   La última posición conocida, UNA fila por conductor. Es
--                      una tabla aparte porque se reescribe cada pocos segundos
--                      y no conviene que cada ubicación reescriba la fila entera
--                      del conductor (ni compita con las ediciones de su perfil).
--
-- Mientras el conductor está en una carrera su ubicación NO pasa por acá: va
-- directo al nodo de la carrera en Realtime Database, para el mapa del
-- pasajero. Esta fila queda con la última posición de antes de la carrera, y
-- por eso el despacho exige que sea RECIENTE (updated_at) y que no esté en
-- carrera.

ALTER TABLE drivers
    ADD COLUMN is_online boolean NOT NULL DEFAULT false,
    ADD COLUMN on_ride   boolean NOT NULL DEFAULT false;

CREATE TABLE driver_locations (
    driver_id  uuid                  NOT NULL,
    -- geography y no geometry: las distancias salen en METROS reales sobre la
    -- esfera, sin proyectar. El punto se construye con (longitud, latitud), en
    -- ESE orden: ST_MakePoint(lng, lat).
    location   geography(Point, 4326) NOT NULL,
    updated_at timestamptz           NOT NULL DEFAULT now(),

    CONSTRAINT pk_driver_locations PRIMARY KEY (driver_id),
    -- Si el conductor desaparece, su ubicación también.
    CONSTRAINT fk_driver_locations_driver
        FOREIGN KEY (driver_id) REFERENCES drivers (user_id) ON DELETE CASCADE
);

-- El índice que hace barata la búsqueda por radio (ST_DWithin).
CREATE INDEX idx_driver_locations_location ON driver_locations USING GIST (location);
