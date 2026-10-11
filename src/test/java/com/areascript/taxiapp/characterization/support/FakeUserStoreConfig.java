package com.areascript.taxiapp.characterization.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

// Pone la base de usuarios en memoria en lugar de la de Postgres (que los tests
// HTTP desactivan con taxiapp.persistence.postgres=false).
@TestConfiguration(proxyBeanMethods = false)
public class FakeUserStoreConfig {

    @Bean
    public FakeUserAccounts fakeUserAccounts() {
        return new FakeUserAccounts();
    }

    @Bean
    public FakeUserProfiles fakeUserProfiles() {
        return new FakeUserProfiles();
    }

    @Bean
    public FakeDriverPresence fakeDriverPresence() {
        return new FakeDriverPresence();
    }
}
