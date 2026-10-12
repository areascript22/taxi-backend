package com.areascript.taxiapp.characterization.support;

import com.areascript.taxiapp.user.domain.UserAccount;
import com.areascript.taxiapp.user.domain.UserAccounts;
import com.areascript.taxiapp.user.domain.UserRole;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

// La base de usuarios en memoria para los tests HTTP, que corren sin Postgres.
//
// Hace el papel de la tabla users: guarda a cada persona con su rol. El
// arnés (CharacterizationTestBase) la siembra al fabricar un token, con el rol
// que ese token "tendría" en la base; así el servidor autoriza por el mismo
// camino que en producción (el rol guardado), no por los claims.
public class FakeUserAccounts implements UserAccounts {

    private final Map<String, UserAccount> byFirebaseUid = new ConcurrentHashMap<>();
    // Quien necesite enterarse de que una persona se borró (en producción lo
    // hace la cascada de la base: sus perfiles y vehículos desaparecen con ella).
    private final List<Consumer<String>> removalListeners = new CopyOnWriteArrayList<>();

    @Override
    public UserAccount findOrProvision(String firebaseUid, String email, String displayName) {
        return byFirebaseUid.computeIfAbsent(firebaseUid, uid -> new UserAccount(
                UUID.nameUUIDFromBytes(uid.getBytes()),
                uid,
                email == null ? "" : email,
                displayName == null ? "" : displayName,
                UserRole.USER,
                false));
    }

    @Override
    public void setRole(String firebaseUid, UserRole role) {
        UserAccount current = findOrProvision(firebaseUid, "", "");
        byFirebaseUid.put(firebaseUid, new UserAccount(
                current.id(), current.firebaseUid(), current.email(), current.displayName(), role, current.blocked()));
    }

    @Override
    public void remove(String firebaseUid) {
        byFirebaseUid.remove(firebaseUid);
        removalListeners.forEach(listener -> listener.accept(firebaseUid));
    }

    // ---- Para el arnés y las aserciones ----

    public void onRemove(Consumer<String> listener) {
        removalListeners.add(listener);
    }

    public void seed(String firebaseUid, String email, String displayName, UserRole role) {
        byFirebaseUid.put(firebaseUid, new UserAccount(
                UUID.nameUUIDFromBytes(firebaseUid.getBytes()),
                firebaseUid,
                email == null ? "" : email,
                displayName == null ? "" : displayName,
                role,
                false));
    }

    public UserRole roleOf(String firebaseUid) {
        UserAccount account = byFirebaseUid.get(firebaseUid);
        return account == null ? null : account.role();
    }

    public boolean exists(String firebaseUid) {
        return byFirebaseUid.containsKey(firebaseUid);
    }

    public void clear() {
        byFirebaseUid.clear();
        removalListeners.clear();
    }
}
