package com.areascript.taxiapp.appversion.api;

import com.areascript.taxiapp.appversion.application.AppVersionService;
import com.areascript.taxiapp.appversion.application.AppVersionService.VersionCheck;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Endpoint PÚBLICO (no exige token): las apps lo consultan al arrancar, antes
// de que el usuario inicie sesión, para saber si su versión todavía se admite.
@RestController
@RequestMapping("/api/public")
public class AppVersionController {

    private final AppVersionService appVersionService;

    public AppVersionController(AppVersionService appVersionService) {
        this.appVersionService = appVersionService;
    }

    // GET /api/public/app-version?app=driver&version=1.0.7
    //
    // 400 si `app` no es una app conocida. Una `version` que no se puede
    // interpretar no es un error: responde updateRequired=false.
    @GetMapping("/app-version")
    public ResponseEntity<AppVersionResponse> check(
            @RequestParam String app,
            @RequestParam String version
    ) {
        VersionCheck result = appVersionService.check(app, version);
        return ResponseEntity.ok(new AppVersionResponse(
                result.updateRequired(), result.minimumVersion(), result.storeUrl()));
    }

    // Los nombres de los campos son el contrato con las apps: no renombrar.
    public record AppVersionResponse(boolean updateRequired, String minimumVersion, String storeUrl) {
    }
}
