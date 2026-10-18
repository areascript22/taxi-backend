package com.areascript.taxiapp.user.infra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

// Una fila de la tabla `drivers`: lo PROPIO del conductor. La identidad (nombre,
// correo, rol, bloqueo) está en `users`; esta fila cuelga de ella por user_id.
//
// Es la representación PERSISTENTE (JPA), no el modelo de negocio: el dominio
// usa `Driver` (un record inmutable) y esta clase no sale de infra. Se llama
// "Row" para no confundirla con una entidad de dominio.
//
// JPA exige clase mutable y constructor sin argumentos; por eso no es un record.
@Entity
@Table(name = "drivers")
public class DriverRow {

    // También es clave foránea a users(id).
    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "phone_number", nullable = false)
    private String phoneNumber = "";

    @Column(name = "photo_url")
    private String photoUrl;

    @Column(name = "rating", nullable = false)
    private double rating = 5.0;

    // Valores: pending | approved | rejected (CHECK en la base).
    @Column(name = "approval_status", nullable = false)
    private String approvalStatus = "pending";

    @Column(name = "approved_at")
    private Instant approvedAt;

    // users.id del admin que aprobó.
    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    // users.id del admin que rechazó.
    @Column(name = "rejected_by")
    private UUID rejectedBy;

    // Por instalación de la app del conductor.
    @Column(name = "language")
    private String language;

    @Column(name = "fcm_token")
    private String fcmToken;

    @Column(name = "vehicle_id")
    private String vehicleId;

    // Presencia (V5). Los pone el conductor (is_online) y el servidor (on_ride);
    // NUNCA la importacion desde Firestore, que no sabe nada de esto.
    @Column(name = "is_online", nullable = false)
    private boolean online;

    @Column(name = "on_ride", nullable = false)
    private boolean onRide;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DriverRow() {
    }

    public DriverRow(UUID userId) {
        this.userId = userId;
    }

    public UUID getUserId() { return userId; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public String getPhotoUrl() { return photoUrl; }
    public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }

    public double getRating() { return rating; }
    public void setRating(double rating) { this.rating = rating; }

    public String getApprovalStatus() { return approvalStatus; }
    public void setApprovalStatus(String approvalStatus) { this.approvalStatus = approvalStatus; }

    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }

    public UUID getApprovedBy() { return approvedBy; }
    public void setApprovedBy(UUID approvedBy) { this.approvedBy = approvedBy; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public Instant getRejectedAt() { return rejectedAt; }
    public void setRejectedAt(Instant rejectedAt) { this.rejectedAt = rejectedAt; }

    public UUID getRejectedBy() { return rejectedBy; }
    public void setRejectedBy(UUID rejectedBy) { this.rejectedBy = rejectedBy; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public String getFcmToken() { return fcmToken; }
    public void setFcmToken(String fcmToken) { this.fcmToken = fcmToken; }

    public boolean isOnline() { return online; }
    public void setOnline(boolean online) { this.online = online; }

    public boolean isOnRide() { return onRide; }
    public void setOnRide(boolean onRide) { this.onRide = onRide; }

    public String getVehicleId() { return vehicleId; }
    public void setVehicleId(String vehicleId) { this.vehicleId = vehicleId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
