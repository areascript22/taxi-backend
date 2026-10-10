package com.areascript.taxiapp.controller;

import com.areascript.taxiapp.common.security.AuthenticatedUser;
import com.areascript.taxiapp.dto.DriverDTO;
import com.areascript.taxiapp.dto.DriverPageDTO;
import com.areascript.taxiapp.service.DriverAdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Panel de administración. Todo el controller exige ROLE_ADMIN (claim "admin"
// del token); cambiar roles es más estricto y lo declara cada método.
//
// Los errores de negocio (404 conductor inexistente, 403 jerarquía de roles,
// 400 datos inválidos, 409 conductor no aprobado, 500 Firebase) los traduce
// GlobalExceptionHandler.
@RestController
@RequestMapping("/api/drivers")
@PreAuthorize("hasRole('ADMIN')")
public class DriverAdminController {

    private final DriverAdminService driverAdminService;

    public DriverAdminController(DriverAdminService driverAdminService) {
        this.driverAdminService = driverAdminService;
    }

    @DeleteMapping("/{uid}")
    public ResponseEntity<Void> deleteDriver(
            @PathVariable String uid,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        driverAdminService.deleteDriver(uid, user.superUser());
        return ResponseEntity.noContent().build();
    }

    // Sin pageSize: fetch completo (lo usa el buscador del panel admin, que
    // necesita ver a todos los conductores para filtrar). Con pageSize: una
    // página vía cursor (lo usa la navegación normal de la lista).
    @GetMapping
    public ResponseEntity<DriverPageDTO> listDrivers(
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String cursor
    ) {
        DriverPageDTO page = pageSize != null
                ? driverAdminService.listDriversPage(pageSize, cursor)
                : driverAdminService.listAllDrivers();
        return ResponseEntity.ok(page);
    }

    // Refresco puntual de un conductor -- lo usa la app al volver de la
    // pantalla de detalle, para resincronizar ese registro sin recargar (y
    // perder la posición de) la lista paginada completa.
    @GetMapping("/{uid}")
    public ResponseEntity<DriverDTO> getDriver(@PathVariable String uid) {
        return ResponseEntity.ok(driverAdminService.getDriver(uid));
    }

    // Cambiar roles exige superuser, o ser el fundador del proyecto aunque
    // todavía no tenga el claim. El @PreAuthorize del método REEMPLAZA al de la
    // clase (no se suma): por eso un fundador sin el claim "admin" llega acá,
    // como antes de pasar a Spring Security.
    @PutMapping("/{uid}/role")
    @PreAuthorize("hasAnyRole('FOUNDER', 'SUPERUSER')")
    public ResponseEntity<Void> updateDriverRole(
            @PathVariable String uid,
            @RequestBody UpdateDriverRoleRequest body
    ) {
        driverAdminService.updateDriverRole(uid, body.role());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{uid}/approval-status")
    public ResponseEntity<Void> updateApprovalStatus(
            @PathVariable String uid,
            @RequestBody UpdateApprovalStatusRequest body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        driverAdminService.updateApprovalStatus(
                uid,
                body.status(),
                body.reason(),
                user.uid(),
                user.superUser()
        );
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{uid}/block-status")
    public ResponseEntity<Void> updateBlockStatus(
            @PathVariable String uid,
            @RequestBody UpdateBlockStatusRequest body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        driverAdminService.updateBlockStatus(
                uid,
                body.blocked(),
                body.reason(),
                user.uid(),
                user.superUser()
        );
        return ResponseEntity.noContent().build();
    }
}
