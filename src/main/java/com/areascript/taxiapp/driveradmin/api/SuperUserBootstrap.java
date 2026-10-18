package com.areascript.taxiapp.driveradmin.api;

import com.areascript.taxiapp.driveradmin.application.FounderBootstrapService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

// Se ejecuta en cada arranque del backend y le pide a FounderBootstrapService
// que garantice los privilegios de la cuenta dueña del proyecto. La lógica (y
// su explicación) vive en el caso de uso; esto solo es el disparador.
@Component
public class SuperUserBootstrap implements CommandLineRunner {

    private final FounderBootstrapService founderBootstrapService;

    public SuperUserBootstrap(FounderBootstrapService founderBootstrapService) {
        this.founderBootstrapService = founderBootstrapService;
    }

    @Override
    public void run(String... args) {
        founderBootstrapService.ensureFounderIsSuperUser();
    }
}
