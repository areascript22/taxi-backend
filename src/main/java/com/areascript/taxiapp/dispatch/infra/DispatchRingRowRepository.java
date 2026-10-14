package com.areascript.taxiapp.dispatch.infra;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// Acceso a los anillos de búsqueda. Solo lo usa infra.
public interface DispatchRingRowRepository extends JpaRepository<DispatchRingRow, Integer> {

    // En el orden en que se recorren: del más cercano al más lejano.
    List<DispatchRingRow> findAllByOrderByRingNumberAsc();
}
