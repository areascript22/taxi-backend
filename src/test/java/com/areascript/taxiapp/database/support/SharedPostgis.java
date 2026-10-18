package com.areascript.taxiapp.database.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

// Un único Postgres (con PostGIS) compartido por todos los tests de base de
// datos de una corrida: arrancar un contenedor por clase de test cuesta ~10 s
// cada vez. Se levanta la primera vez que se pide y lo recoge Ryuk (el reaper de
// Testcontainers) cuando termina la JVM.
//
// La imagen tiene que ser la MISMA que usa compose.yaml.
public final class SharedPostgis {

    private static final DockerImageName IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres");

    private static PostgreSQLContainer container;

    private SharedPostgis() {
    }

    public static boolean dockerAvailable() {
        return DockerClientFactory.instance().isDockerAvailable();
    }

    // Para @DynamicPropertySource de los tests con @DataJpaTest.
    public static void registerDatasource(DynamicPropertyRegistry registry) {
        PostgreSQLContainer postgres = get();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static synchronized PostgreSQLContainer get() {
        if (container == null) {
            container = new PostgreSQLContainer(IMAGE);
            container.start();
        }
        return container;
    }
}
