package com.areascript.taxiapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class SchedulingConfig {

    // Usado por RideService para agendar la auto-cancelación de solicitudes
    // 'pending' (ver PENDING_REQUEST_EXPIRY_SECONDS): un pool chico alcanza
    // porque cada tarea agendada solo corre una transacción corta sobre
    // Realtime Database y termina.
    //
    // Necesario declararlo a mano: en este stack (Spring Boot 4.0.8 /
    // Framework 7.0.9) TaskSchedulingAutoConfiguration no registra un
    // TaskScheduler por defecto sin este bean -- confirmado en runtime
    // (UnsatisfiedDependencyException: "No qualifying bean of type
    // TaskScheduler"). El inspector estático de IntelliJ había marcado esto
    // como bean duplicado con "TaskSchedulingConfigurations.class", pero era
    // un falso positivo del IDE: el ApplicationContext real nunca llegó a
    // registrar ese segundo bean.
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(5);
        scheduler.setThreadNamePrefix("ride-expiry-");
        scheduler.initialize();
        return scheduler;
    }
}
