package com.areascript.taxiapp.driveradmin.infra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

// Una fila de la tabla `drivers`. Es la representación PERSISTENTE (JPA), no el
// modelo de negocio: el dominio usa `Driver` (un record inmutable) y esta clase
// no sale de infra. Se llama "Row" para no confundirla con una entidad de
// dominio.
//
// JPA exige clase mutable y constructor sin argumentos; por eso no es un record.
@Entity
@Table(name = "drivers")
public class DriverRow {

    @Id
    @Column(name = "uid")
    private String uid;

    @Column(name = "first_name", nullable = false)
    private String firstName = "";

    @Column(name = "last_name", nullable = false)
    private String lastName = "";

    @Column(name = "email", nullable = false)
    private String email = "";

    @Column(name = "phone_number", nullable = false)
    private String phoneNumber = "";

    @Column(name = "photo_url")
    private String photoUrl;

    @Column(name = "rating", nullable = false)
    private double rating = 5.0;

    // Valores: driver | admin | superuser (CHECK en la base).
    @Column(name = "role", nullable = false)
    private String role = "driver";

    // Valores: pending | approved | rejected (CHECK en la base).
    @Column(name = "approval_status", nullable = false)
    private String approvalStatus = "pending";

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "rejected_by")
    private String rejectedBy;

    @Column(name = "is_blocked", nullable = false)
    private boolean blocked;

    @Column(name = "block_reason")
    private String blockReason;

    @Column(name = "blocked_at")
    private Instant blockedAt;

    @Column(name = "blocked_by")
    private String blockedBy;

    @Column(name = "language")
    private String language;

    @Column(name = "fcm_token")
    private String fcmToken;

    @Column(name = "vehicle_id")
    private String vehicleId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DriverRow() {
    }

    public DriverRow(String uid) {
        this.uid = uid;
    }

    public String getUid() { return uid; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public String getPhotoUrl() { return photoUrl; }
    public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }

    public double getRating() { return rating; }
    public void setRating(double rating) { this.rating = rating; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getApprovalStatus() { return approvalStatus; }
    public void setApprovalStatus(String approvalStatus) { this.approvalStatus = approvalStatus; }

    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }

    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public Instant getRejectedAt() { return rejectedAt; }
    public void setRejectedAt(Instant rejectedAt) { this.rejectedAt = rejectedAt; }

    public String getRejectedBy() { return rejectedBy; }
    public void setRejectedBy(String rejectedBy) { this.rejectedBy = rejectedBy; }

    public boolean isBlocked() { return blocked; }
    public void setBlocked(boolean blocked) { this.blocked = blocked; }

    public String getBlockReason() { return blockReason; }
    public void setBlockReason(String blockReason) { this.blockReason = blockReason; }

    public Instant getBlockedAt() { return blockedAt; }
    public void setBlockedAt(Instant blockedAt) { this.blockedAt = blockedAt; }

    public String getBlockedBy() { return blockedBy; }
    public void setBlockedBy(String blockedBy) { this.blockedBy = blockedBy; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public String getFcmToken() { return fcmToken; }
    public void setFcmToken(String fcmToken) { this.fcmToken = fcmToken; }

    public String getVehicleId() { return vehicleId; }
    public void setVehicleId(String vehicleId) { this.vehicleId = vehicleId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
