package com.areascript.taxiapp.controller;

import com.areascript.taxiapp.common.config.TaxiappProperties;
import com.areascript.taxiapp.dto.DriverDTO;
import com.areascript.taxiapp.dto.DriverPageDTO;
import com.areascript.taxiapp.security.FirebaseSecurityUtils;
import com.areascript.taxiapp.service.DriverAdminService;
import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Los errores de negocio (404 conductor inexistente, 403 jerarquía de roles,
// 400 datos inválidos, 409 conductor no aprobado, 500 Firebase) los traduce
// GlobalExceptionHandler. El chequeo de admin/superuser de cada endpoint se
// mueve a Spring Security en la fase 2 del refactor.
@RestController
@RequestMapping("/api/drivers")
public class DriverAdminController {

    private final DriverAdminService driverAdminService;
    private final TaxiappProperties.Admin adminSettings;

    public DriverAdminController(DriverAdminService driverAdminService, TaxiappProperties properties) {
        this.driverAdminService = driverAdminService;
        this.adminSettings = properties.admin();
    }

    @DeleteMapping("/{uid}")
    public ResponseEntity<Void> deleteDriver(@PathVariable String uid, HttpServletRequest request) {
        if (!FirebaseSecurityUtils.isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        driverAdminService.deleteDriver(uid, FirebaseSecurityUtils.isSuperUser(request));
        return ResponseEntity.noContent().build();
    }

    // Sin pageSize: fetch completo (lo usa el buscador del panel admin, que
    // necesita ver a todos los conductores para filtrar). Con pageSize: una
    // página vía cursor (lo usa la navegación normal de la lista).
    @GetMapping
    public ResponseEntity<DriverPageDTO> listDrivers(
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String cursor,
            HttpServletRequest request
    ) {
        if (!FirebaseSecurityUtils.isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        DriverPageDTO page = pageSize != null
                ? driverAdminService.listDriversPage(pageSize, cursor)
                : driverAdminService.listAllDrivers();
        return ResponseEntity.ok(page);
    }

    // Refresco puntual de un conductor -- lo usa la app al volver de la
    // pantalla de detalle, para resincronizar ese registro sin recargar (y
    // perder la posición de) la lista paginada completa.
    @GetMapping("/{uid}")
    public ResponseEntity<DriverDTO> getDriver(@PathVariable String uid, HttpServletRequest request) {
        if (!FirebaseSecurityUtils.isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(driverAdminService.getDriver(uid));
    }

    // Cambiar roles exige superuser (o ser el fundador del proyecto, aunque
    // todavía no tenga el claim).
    @PutMapping("/{uid}/role")
    public ResponseEntity<Void> updateDriverRole(
            @PathVariable String uid,
            @RequestBody UpdateDriverRoleRequest body,
            HttpServletRequest request
    ) {
        if (!isFounder(request) && !FirebaseSecurityUtils.isSuperUser(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        driverAdminService.updateDriverRole(uid, body.role());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{uid}/approval-status")
    public ResponseEntity<Void> updateApprovalStatus(
            @PathVariable String uid,
            @RequestBody UpdateApprovalStatusRequest body,
            HttpServletRequest request
    ) {
        if (!FirebaseSecurityUtils.isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        FirebaseToken token = FirebaseSecurityUtils.getToken(request);
        String actorUid = token != null ? token.getUid() : null;
        driverAdminService.updateApprovalStatus(
                uid,
                body.status(),
                body.reason(),
                actorUid,
                FirebaseSecurityUtils.isSuperUser(request)
        );
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{uid}/block-status")
    public ResponseEntity<Void> updateBlockStatus(
            @PathVariable String uid,
            @RequestBody UpdateBlockStatusRequest body,
            HttpServletRequest request
    ) {
        if (!FirebaseSecurityUtils.isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        FirebaseToken token = FirebaseSecurityUtils.getToken(request);
        String actorUid = token != null ? token.getUid() : null;
        driverAdminService.updateBlockStatus(
                uid,
                body.blocked(),
                body.reason(),
                actorUid,
                FirebaseSecurityUtils.isSuperUser(request)
        );
        return ResponseEntity.noContent().build();
    }

    private boolean isFounder(HttpServletRequest request) {
        FirebaseToken token = FirebaseSecurityUtils.getToken(request);
        return token != null && adminSettings.isFounder(token.getUid(), token.getEmail());
    }
}
