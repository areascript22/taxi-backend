package com.areascript.taxiapp.passenger.infra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

// Una fila de la tabla `passengers`: lo PROPIO del pasajero. La identidad
// (nombre, correo, bloqueo) está en `users`; esta fila cuelga de ella por
// user_id. El modelo de negocio del pasajero vivirá en passenger/domain cuando
// la feature lea de esta tabla.
@Entity
@Table(name = "passengers")
public class PassengerRow {

    // También es clave foránea a users(id).
    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "photo_url")
    private String photoUrl;

    @Column(name = "status", nullable = false)
    private String status = "active";

    // Por instalación de la app del pasajero.
    @Column(name = "language")
    private String language;

    @Column(name = "fcm_token")
    private String fcmToken;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PassengerRow() {
    }

    public PassengerRow(UUID userId) {
        this.userId = userId;
    }

    public UUID getUserId() { return userId; }

    public String getPhotoUrl() { return photoUrl; }
    public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public String getFcmToken() { return fcmToken; }
    public void setFcmToken(String fcmToken) { this.fcmToken = fcmToken; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
