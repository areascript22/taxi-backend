package com.areascript.taxiapp.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class TimeConfig {

    // El reloj del servidor. Se inyecta en lugar de llamar a Instant.now()
    // para que las reglas que dependen de la hora (retención del chat, por
    // ejemplo) se puedan probar con un reloj fijo.
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
