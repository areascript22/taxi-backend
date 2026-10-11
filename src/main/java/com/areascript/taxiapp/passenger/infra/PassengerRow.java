package com.areascript.taxiapp.passenger.infra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

// Una fila de la tabla `passengers`. Es la representación persistente (JPA);
// el modelo de negocio del pasajero vivirá en passenger/domain cuando la
// feature lea de esta tabla.
@Entity
@Table(name = "passengers")
public class PassengerRow {

    @Id
    @Column(name = "uid")
    private String uid;

    @Column(name = "first_name", nullable = false)
    private String firstName = "";

    @Column(name = "last_name", nullable = false)
    private String lastName = "";

    @Column(name = "email", nullable = false)
    private String email = "";

    @Column(name = "photo_url")
    private String photoUrl;

    @Column(name = "status", nullable = false)
    private String status = "active";

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

    public PassengerRow(String uid) {
        this.uid = uid;
    }

    public String getUid() { return uid; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

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
