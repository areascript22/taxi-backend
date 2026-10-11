package com.areascript.taxiapp.driveradmin.infra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

// Una fila de la tabla `vehicles`. Ver DriverRow sobre el nombre "Row".
//
// driver_id es una clave foránea a drivers(user_id) en la base; acá se guarda
// como UUID (sin @ManyToOne) a propósito: no hace falta navegar el grafo y evita
// cargas perezosas accidentales.
@Entity
@Table(name = "vehicles")
public class VehicleRow {

    @Id
    @Column(name = "vehicle_id")
    private String vehicleId;

    @Column(name = "driver_id", nullable = false)
    private UUID driverId;

    @Column(name = "plate", nullable = false)
    private String plate = "";

    @Column(name = "brand", nullable = false)
    private String brand = "";

    @Column(name = "model", nullable = false)
    private String model = "";

    @Column(name = "year", nullable = false)
    private int year;

    @Column(name = "color", nullable = false)
    private String color = "";

    @Column(name = "registration_number", nullable = false)
    private String registrationNumber = "";

    @Column(name = "verification_status", nullable = false)
    private String verificationStatus = "pending";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected VehicleRow() {
    }

    public VehicleRow(String vehicleId, UUID driverId) {
        this.vehicleId = vehicleId;
        this.driverId = driverId;
    }

    public String getVehicleId() { return vehicleId; }

    public UUID getDriverId() { return driverId; }

    public String getPlate() { return plate; }
    public void setPlate(String plate) { this.plate = plate; }

    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public int getYear() { return year; }
    public void setYear(int year) { this.year = year; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    public String getRegistrationNumber() { return registrationNumber; }
    public void setRegistrationNumber(String registrationNumber) { this.registrationNumber = registrationNumber; }

    public String getVerificationStatus() { return verificationStatus; }
    public void setVerificationStatus(String verificationStatus) { this.verificationStatus = verificationStatus; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
