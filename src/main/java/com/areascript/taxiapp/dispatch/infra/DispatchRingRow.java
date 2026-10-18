package com.areascript.taxiapp.dispatch.infra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

// Un anillo de búsqueda de conductores (fila de dispatch_rings): hasta qué
// distancia se busca, cuánto se espera y a cuántos se ofrece. Los anillos se
// recorren en orden de ring_number.
@Entity
@Table(name = "dispatch_rings")
public class DispatchRingRow {

    @Id
    @Column(name = "ring_number")
    private int ringNumber;

    @Column(name = "radius_meters", nullable = false)
    private int radiusMeters;

    @Column(name = "wait_seconds", nullable = false)
    private int waitSeconds;

    @Column(name = "max_drivers", nullable = false)
    private int maxDrivers;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    protected DispatchRingRow() {
    }

    public DispatchRingRow(int ringNumber, int radiusMeters, int waitSeconds, int maxDrivers) {
        this.ringNumber = ringNumber;
        this.radiusMeters = radiusMeters;
        this.waitSeconds = waitSeconds;
        this.maxDrivers = maxDrivers;
        this.updatedAt = Instant.now();
    }

    public int getRingNumber() { return ringNumber; }

    public int getRadiusMeters() { return radiusMeters; }
    public void setRadiusMeters(int radiusMeters) { this.radiusMeters = radiusMeters; }

    public int getWaitSeconds() { return waitSeconds; }
    public void setWaitSeconds(int waitSeconds) { this.waitSeconds = waitSeconds; }

    public int getMaxDrivers() { return maxDrivers; }
    public void setMaxDrivers(int maxDrivers) { this.maxDrivers = maxDrivers; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public UUID getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }
}
