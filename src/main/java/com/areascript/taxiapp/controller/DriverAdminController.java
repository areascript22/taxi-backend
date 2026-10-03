package com.areascript.taxiapp.controller;
import com.areascript.taxiapp.dto.DriverDTO;
import com.areascript.taxiapp.dto.DriverPageDTO;
import com.areascript.taxiapp.security.FirebaseSecurityUtils;
import com.areascript.taxiapp.service.DriverAdminService;
import com.areascript.taxiapp.service.DriverApprovalUpdateException;
import com.areascript.taxiapp.service.DriverBlockUpdateException;
import com.areascript.taxiapp.service.DriverDeletionException;
import com.areascript.taxiapp.service.DriverListException;
import com.areascript.taxiapp.service.DriverModerationException;
import com.areascript.taxiapp.service.DriverNotApprovedException;
import com.areascript.taxiapp.service.DriverNotFoundException;
import com.areascript.taxiapp.service.DriverRoleUpdateException;
import com.areascript.taxiapp.service.InvalidApprovalStatusException;
import com.areascript.taxiapp.service.InvalidRoleException;
import com.areascript.taxiapp.service.MissingReasonException;
import com.areascript.taxiapp.service.RoleHierarchyViolationException;
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

@RestController
@RequestMapping("/api/drivers")
public class DriverAdminController {

    private static final String SUPER_ADMIN_UID = "AhsQJcGg49UQkLWiuy6trG0BWzq1";
    private static final String SUPER_ADMIN_EMAIL = "jluisgg2002@gmail.com";

    private final DriverAdminService driverAdminService;

    public DriverAdminController(DriverAdminService driverAdminService) {
        this.driverAdminService = driverAdminService;
    }

    @DeleteMapping("/{uid}")
    public ResponseEntity<Void> deleteDriver(@PathVariable String uid, HttpServletRequest request) {
        if (!FirebaseSecurityUtils.isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            driverAdminService.deleteDriver(uid, FirebaseSecurityUtils.isSuperUser(request));
            return ResponseEntity.noContent().build();
        } catch (DriverNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (RoleHierarchyViolationException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (DriverDeletionException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
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
        try {
            DriverPageDTO page = pageSize != null
                    ? driverAdminService.listDriversPage(pageSize, cursor)
                    : driverAdminService.listAllDrivers();
            return ResponseEntity.ok(page);
        } catch (DriverListException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // Refresco puntual de un conductor -- lo usa la app al volver de la
    // pantalla de detalle, para resincronizar ese registro sin recargar (y
    // perder la posición de) la lista paginada completa.
    @GetMapping("/{uid}")
    public ResponseEntity<DriverDTO> getDriver(@PathVariable String uid, HttpServletRequest request) {
        if (!FirebaseSecurityUtils.isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            return ResponseEntity.ok(driverAdminService.getDriver(uid));
        } catch (DriverNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (DriverListException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PutMapping("/{uid}/role")
    public ResponseEntity<Void> updateDriverRole(
            @PathVariable String uid,
            @RequestBody UpdateDriverRoleRequest body,
            HttpServletRequest request
    ) {
        if (!isSuperAdmin(request) && !FirebaseSecurityUtils.isSuperUser(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            driverAdminService.updateDriverRole(uid, body.role());
            return ResponseEntity.noContent().build();
        } catch (DriverNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (InvalidRoleException e) {
            return ResponseEntity.badRequest().build();
        } catch (RoleHierarchyViolationException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (DriverRoleUpdateException | DriverModerationException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
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
        try {
            driverAdminService.updateApprovalStatus(
                    uid,
                    body.status(),
                    body.reason(),
                    actorUid,
                    FirebaseSecurityUtils.isSuperUser(request)
            );
            return ResponseEntity.noContent().build();
        } catch (DriverNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (InvalidApprovalStatusException | MissingReasonException e) {
            return ResponseEntity.badRequest().build();
        } catch (RoleHierarchyViolationException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (DriverApprovalUpdateException | DriverModerationException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
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
        try {
            driverAdminService.updateBlockStatus(
                    uid,
                    body.blocked(),
                    body.reason(),
                    actorUid,
                    FirebaseSecurityUtils.isSuperUser(request)
            );
            return ResponseEntity.noContent().build();
        } catch (DriverNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (MissingReasonException e) {
            return ResponseEntity.badRequest().build();
        } catch (DriverNotApprovedException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        } catch (RoleHierarchyViolationException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (DriverBlockUpdateException | DriverModerationException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private boolean isSuperAdmin(HttpServletRequest request) {
        FirebaseToken token = FirebaseSecurityUtils.getToken(request);
        return token != null
                && SUPER_ADMIN_UID.equals(token.getUid())
                && SUPER_ADMIN_EMAIL.equalsIgnoreCase(token.getEmail());
    }
}
