package com.areascript.taxiapp.controller;
import com.areascript.taxiapp.dto.DriverDTO;
import com.areascript.taxiapp.security.FirebaseSecurityUtils;
import com.areascript.taxiapp.service.DriverAdminService;
import com.areascript.taxiapp.service.DriverDeletionException;
import com.areascript.taxiapp.service.DriverListException;
import com.areascript.taxiapp.service.DriverNotFoundException;
import com.areascript.taxiapp.service.DriverRoleUpdateException;
import com.areascript.taxiapp.service.InvalidRoleException;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    @GetMapping
    public ResponseEntity<List<DriverDTO>> listDrivers(HttpServletRequest request) {
        if (!FirebaseSecurityUtils.isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            return ResponseEntity.ok(driverAdminService.listDrivers());
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
        } catch (DriverRoleUpdateException e) {
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
