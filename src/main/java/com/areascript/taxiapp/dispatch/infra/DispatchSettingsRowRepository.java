package com.areascript.taxiapp.dispatch.infra;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

// Acceso a la fila única de dispatch_settings (id = 1). Solo lo usa infra.
public interface DispatchSettingsRowRepository extends JpaRepository<DispatchSettingsRow, Integer> {

    // La fila bloqueada para escribir hasta que termine la transacción: dos
    // administradores guardando a la vez se ponen en fila, y el segundo ve la
    // versión que dejó el primero (y recibe el conflicto) en vez de pisarlo.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from DispatchSettingsRow s where s.id = " + DispatchSettingsRow.SINGLE_ROW_ID)
    Optional<DispatchSettingsRow> findSingleForUpdate();
}
