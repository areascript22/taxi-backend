package com.areascript.taxiapp.database;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// Las migraciones de Flyway contra un Postgres REAL con PostGIS, en un
// contenedor de Docker -- la misma imagen que usa compose.yaml. H2 no sirve
// acá: no tiene PostGIS y se comporta distinto a Postgres.
//
// Si Docker no está disponible el test se omite (no falla), para no romper el
// build en una máquina sin Docker.
class DatabaseMigrationTest {

    // Tiene que ser la MISMA imagen que compose.yaml.
    private static final DockerImageName POSTGIS =
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres");

    @Test
    void las_migraciones_se_aplican_y_dejan_postgis_funcionando() throws SQLException {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker no está disponible");

        try (PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGIS)) {
            postgres.start();

            Flyway flyway = Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .load();

            MigrateResult first = flyway.migrate();
            assertThat(first.success).isTrue();
            assertThat(first.migrationsExecuted).isPositive();

            // Idempotente: volver a migrar no ejecuta nada (es lo que pasa en
            // cada arranque del servidor).
            assertThat(flyway.migrate().migrationsExecuted).isZero();

            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 Statement statement = connection.createStatement()) {

                try (ResultSet version = statement.executeQuery("SELECT postgis_version()")) {
                    assertThat(version.next()).isTrue();
                    assertThat(version.getString(1)).startsWith("3.");
                }

                // Lo que usará el despacho por cercanía: ¿está un punto a menos
                // de X metros de otro? Los puntos son lng,lat (¡en ese orden!).
                try (ResultSet near = statement.executeQuery(
                        "SELECT ST_DWithin("
                                + "ST_SetSRID(ST_MakePoint(-78.6546, -1.6635), 4326)::geography,"
                                + "ST_SetSRID(ST_MakePoint(-78.6500, -1.6700), 4326)::geography,"
                                + "1000)")) {
                    assertThat(near.next()).isTrue();
                    assertThat(near.getBoolean(1)).as("a ~850 m, dentro de 1 km").isTrue();
                }

                try (ResultSet far = statement.executeQuery(
                        "SELECT ST_DWithin("
                                + "ST_SetSRID(ST_MakePoint(-78.6546, -1.6635), 4326)::geography,"
                                + "ST_SetSRID(ST_MakePoint(-78.6500, -1.6700), 4326)::geography,"
                                + "500)")) {
                    assertThat(far.next()).isTrue();
                    assertThat(far.getBoolean(1)).as("a ~850 m, fuera de 500 m").isFalse();
                }
            }
        }
    }
}
