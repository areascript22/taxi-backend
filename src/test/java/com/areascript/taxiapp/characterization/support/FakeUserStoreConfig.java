package com.areascript.taxiapp.characterization.support;

import com.areascript.taxiapp.common.firebase.FirebaseProperties;
import com.areascript.taxiapp.driveradmin.domain.DriverRepository;
import com.areascript.taxiapp.notification.domain.DeviceRegistry;
import com.google.cloud.firestore.Firestore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

// Pone el almacenamiento de personas en memoria en lugar del de Postgres (que
// los tests HTTP desactivan con taxiapp.persistence.postgres=false).
//
// La base de usuarios, los perfiles y la presencia son fakes propios. El
// conductor y el dispositivo se guardan como DOCUMENTOS del FakeFirestore (ver
// DocumentStoreDriverRepository): así los tests siembran y miran documentos
// sin saber dónde vive el dato en producción.
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

    @Bean
    public FakeDispatchPlans fakeDispatchPlans() {
        return new FakeDispatchPlans();
    }

    @Bean
    public FakeDispatchConfigStore fakeDispatchConfigStore() {
        return new FakeDispatchConfigStore();
    }

    @Bean
    public DriverRepository documentStoreDriverRepository(
            Firestore firestore, FirebaseProperties firebaseProperties) {
        return new DocumentStoreDriverRepository(firestore, firebaseProperties);
    }

    @Bean
    public DeviceRegistry documentStoreDeviceRegistry(
            Firestore firestore, FirebaseProperties firebaseProperties) {
        return new DocumentStoreDeviceRegistry(firestore, firebaseProperties);
    }
}
