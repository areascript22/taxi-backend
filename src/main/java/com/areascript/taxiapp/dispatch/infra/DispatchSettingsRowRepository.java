package com.areascript.taxiapp.dispatch.infra;

import org.springframework.data.jpa.repository.JpaRepository;

// Acceso a la fila única de dispatch_settings (id = 1). Solo lo usa infra.
public interface DispatchSettingsRowRepository extends JpaRepository<DispatchSettingsRow, Integer> {
}
