package com.areascript.taxiapp.dispatch.api;

import com.areascript.taxiapp.common.security.AuthenticatedUser;
import com.areascript.taxiapp.dispatch.application.DispatchConfigService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Panel de configuración del despacho (driver_app, pestaña de administración).
//
//   Ver (GET):              cualquier admin.
//   Editar (PUT):           el superuser siempre; un admin solo si el superuser
//                           encendió "los admins pueden editar" (lo decide el
//                           caso de uso con la configuración vigente, no un rol).
//   Interruptor (PUT):      solo el superuser (o el fundador, igual que cambiar
//                           roles).
//
// Los errores (400 valores inválidos, 403 sin permiso, 409 versión vieja) los
// traduce GlobalExceptionHandler.
@RestController
@RequestMapping("/api/admin/dispatch/config")
@PreAuthorize("hasRole('ADMIN')")
public class DispatchConfigController {

    private static final int DEFAULT_HISTORY_LIMIT = 20;

    private final DispatchConfigService service;

    public DispatchConfigController(DispatchConfigService service) {
        this.service = service;
    }

    @GetMapping
    public DispatchConfigResponse get(Authentication authentication) {
        return DispatchConfigResponse.from(service.get(isSuperUser(authentication)));
    }

    @PutMapping
    public DispatchConfigResponse update(
            @RequestBody UpdateDispatchConfigRequest body,
            @AuthenticationPrincipal AuthenticatedUser user,
            Authentication authentication
    ) {
        return DispatchConfigResponse.from(
                service.update(body.toCommand(), user.uid(), isSuperUser(authentication)));
    }

    // El @PreAuthorize del método REEMPLAZA al de la clase (no se suma): por eso
    // el fundador, que puede no tener todavía el rol, llega acá.
    @PutMapping("/admin-editing")
    @PreAuthorize("hasAnyRole('FOUNDER', 'SUPERUSER')")
    public DispatchConfigResponse setAdminEditing(
            @RequestBody UpdateAdminEditingRequest body,
            @AuthenticationPrincipal AuthenticatedUser user,
            Authentication authentication
    ) {
        return DispatchConfigResponse.from(
                service.setAdminsCanEdit(body.requireEnabled(), user.uid(), isSuperUser(authentication)));
    }

    @GetMapping("/history")
    public DispatchConfigHistoryResponse history(
            @RequestParam(defaultValue = "" + DEFAULT_HISTORY_LIMIT) int limit,
            @RequestParam(required = false) Long beforeId
    ) {
        return DispatchConfigHistoryResponse.from(service.history(limit, beforeId), limit);
    }

    // Superuser = el rol, o el fundador del proyecto (que puede no tenerlo aún).
    private static boolean isSuperUser(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SUPERUSER") || a.getAuthority().equals("ROLE_FOUNDER"));
    }
}
