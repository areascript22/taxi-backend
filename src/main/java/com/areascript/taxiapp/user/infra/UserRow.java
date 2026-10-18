package com.areascript.taxiapp.user.infra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

// Una fila de la tabla `users`: la IDENTIDAD de una persona, sea pasajero,
// conductor o ambos. Los datos propios de cada app viven en drivers y
// passengers, que cuelgan de esta fila por user_id.
//
// `id` es la identidad INTERNA (la que referencian las demás tablas);
// `firebaseUid` es la EXTERNA, la que viene en el token de Firebase Auth.
// Si cambia el proveedor de autenticación, solo cambia esta última.
@Entity
@Table(name = "users")
public class UserRow {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "firebase_uid", nullable = false)
    private String firebaseUid;

    @Column(name = "email", nullable = false)
    private String email = "";

    @Column(name = "first_name", nullable = false)
    private String firstName = "";

    @Column(name = "last_name", nullable = false)
    private String lastName = "";

    // Clave foránea a roles(code): user | admin | superuser.
    @Column(name = "role_code", nullable = false)
    private String roleCode = "user";

    @Column(name = "is_blocked", nullable = false)
    private boolean blocked;

    @Column(name = "block_reason")
    private String blockReason;

    @Column(name = "blocked_at")
    private Instant blockedAt;

    // users.id de quien bloqueó (null si ya no existe).
    @Column(name = "blocked_by")
    private UUID blockedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserRow() {
    }

    public UserRow(UUID id, String firebaseUid) {
        this.id = id;
        this.firebaseUid = firebaseUid;
    }

    public UUID getId() { return id; }

    public String getFirebaseUid() { return firebaseUid; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getRoleCode() { return roleCode; }
    public void setRoleCode(String roleCode) { this.roleCode = roleCode; }

    public boolean isBlocked() { return blocked; }
    public void setBlocked(boolean blocked) { this.blocked = blocked; }

    public String getBlockReason() { return blockReason; }
    public void setBlockReason(String blockReason) { this.blockReason = blockReason; }

    public Instant getBlockedAt() { return blockedAt; }
    public void setBlockedAt(Instant blockedAt) { this.blockedAt = blockedAt; }

    public UUID getBlockedBy() { return blockedBy; }
    public void setBlockedBy(UUID blockedBy) { this.blockedBy = blockedBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
