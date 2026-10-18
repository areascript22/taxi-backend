-- Esquema base. Por ahora la base solo habilita PostGIS: las tablas llegan con
-- cada paso de la migración (usuarios, vehículos, despacho por cercanía).
--
-- REGLA: una migración ya aplicada NO se edita nunca. Flyway guarda su checksum
-- y se niega a arrancar si cambió. Un cambio nuevo es un archivo nuevo
-- (V2__..., V3__...).

CREATE EXTENSION IF NOT EXISTS postgis;
