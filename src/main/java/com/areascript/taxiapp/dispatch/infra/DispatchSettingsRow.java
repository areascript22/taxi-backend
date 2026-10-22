package com.areascript.taxiapp.dispatch.infra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

// La ÚNICA fila de dispatch_settings (id = 1, lo impone un CHECK): parámetros
// globales del despacho que un administrador puede ajustar sin desplegar.
@Entity
@Table(name = "dispatch_settings")
public class DispatchSettingsRow {

    public static final int SINGLE_ROW_ID = 1;

    @Id
    @Column(name = "id")
    private int id = SINGLE_ROW_ID;

    @Column(name = "location_max_age_seconds", nullable = false)
    private int locationMaxAgeSeconds;

    @Column(name = "driver_ping_interval_seconds", nullable = false)
    private int driverPingIntervalSeconds;

    @Column(name = "driver_ping_min_move_meters", nullable = false)
    private int driverPingMinMoveMeters;

    // Interruptor que solo mueve el superuser: encendido, los admins también
    // pueden editar anillos y frescura.
    @Column(name = "admins_can_edit", nullable = false)
    private boolean adminsCanEdit;

    // Sube con cada edición de la configuración (ver V6). No es @Version de JPA a
    // propósito: la comparación la hace el caso de uso con la versión que vio
    // quien edita, bajo el bloqueo de la fila.
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // users.id de quien la cambió por última vez; null = valores iniciales.
    @Column(name = "updated_by")
    private UUID updatedBy;

    protected DispatchSettingsRow() {
    }

    public int getId() { return id; }

    public int getLocationMaxAgeSeconds() { return locationMaxAgeSeconds; }
    public void setLocationMaxAgeSeconds(int value) { this.locationMaxAgeSeconds = value; }

    public int getDriverPingIntervalSeconds() { return driverPingIntervalSeconds; }
    public void setDriverPingIntervalSeconds(int value) { this.driverPingIntervalSeconds = value; }

    public int getDriverPingMinMoveMeters() { return driverPingMinMoveMeters; }
    public void setDriverPingMinMoveMeters(int value) { this.driverPingMinMoveMeters = value; }

    public boolean isAdminsCanEdit() { return adminsCanEdit; }
    public void setAdminsCanEdit(boolean adminsCanEdit) { this.adminsCanEdit = adminsCanEdit; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public UUID getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }
}
