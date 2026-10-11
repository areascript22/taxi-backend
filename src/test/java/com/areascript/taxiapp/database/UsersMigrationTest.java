package com.areascript.taxiapp.database;

import org.flywaydb.core.Flyway;
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

// V3 reestructura tablas que YA tienen datos (las de V2). Un test sobre una base
// vacía no prueba lo importante: que las filas se copien bien. Este test aplica
// V1 y V2, inserta datos con la forma de V2, aplica V3 y comprueba el resultado.
//
// Se omite si Docker no está disponible.
class UsersMigrationTest {

    private static final DockerImageName POSTGIS =
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres");

    @Test
    void v3_conserva_los_datos_de_v2_en_el_modelo_nuevo() throws SQLException {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker no está disponible");

        try (PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGIS)) {
            postgres.start();

            Flyway upToV2 = Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .target("2")
                    .load();
            upToV2.migrate();

            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 Statement statement = connection.createStatement()) {

                insertV2Data(statement);

                Flyway latest = Flyway.configure()
                        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                        .locations("classpath:db/migration")
                        .load();
                assertThat(latest.migrate().success).isTrue();

                assertThat(count(statement, "users")).as("founder, d1, d2 y p1").isEqualTo(4);
                assertThat(count(statement, "drivers")).isEqualTo(3);
                assertThat(count(statement, "passengers")).as("d1 (también pasajero) y p1").isEqualTo(2);
                assertThat(count(statement, "vehicles")).isEqualTo(1);

                // 'driver' ya no es un rol; admin y superuser se conservan.
                assertThat(scalar(statement, "SELECT role_code FROM users WHERE firebase_uid = 'd1'"))
                        .isEqualTo("user");
                assertThat(scalar(statement, "SELECT role_code FROM users WHERE firebase_uid = 'd2'"))
                        .isEqualTo("admin");
                assertThat(scalar(statement, "SELECT role_code FROM users WHERE firebase_uid = 'founder'"))
                        .isEqualTo("superuser");

                // La misma persona (d1) como conductor y pasajero: UNA fila,
                // con los nombres del conductor.
                assertThat(scalar(statement, "SELECT first_name FROM users WHERE firebase_uid = 'd1'"))
                        .isEqualTo("Ana Conductora");
                assertThat(scalar(statement,
                        "SELECT count(*) FROM passengers p JOIN users u ON u.id = p.user_id "
                                + "WHERE u.firebase_uid = 'd1'")).isEqualTo("1");

                // approved_by / blocked_by: de uid de Firebase a users.id.
                assertThat(scalar(statement,
                        "SELECT a.firebase_uid FROM drivers d "
                                + "JOIN users u ON u.id = d.user_id "
                                + "JOIN users a ON a.id = d.approved_by "
                                + "WHERE u.firebase_uid = 'd1'")).isEqualTo("founder");
                assertThat(scalar(statement,
                        "SELECT a.firebase_uid FROM users u "
                                + "JOIN users a ON a.id = u.blocked_by "
                                + "WHERE u.firebase_uid = 'd1'")).isEqualTo("founder");

                // Vehículo y vehículo actual del conductor.
                assertThat(scalar(statement,
                        "SELECT u.firebase_uid FROM vehicles v "
                                + "JOIN users u ON u.id = v.driver_id WHERE v.vehicle_id = 'v1'")).isEqualTo("d1");
                assertThat(scalar(statement,
                        "SELECT d.vehicle_id FROM drivers d JOIN users u ON u.id = d.user_id "
                                + "WHERE u.firebase_uid = 'd1'")).isEqualTo("v1");

                // Cada app conserva su token.
                assertThat(scalar(statement,
                        "SELECT d.fcm_token FROM drivers d JOIN users u ON u.id = d.user_id "
                                + "WHERE u.firebase_uid = 'd1'")).isEqualTo("tok-driver");
                assertThat(scalar(statement,
                        "SELECT p.fcm_token FROM passengers p JOIN users u ON u.id = p.user_id "
                                + "WHERE u.firebase_uid = 'd1'")).isEqualTo("tok-passenger");

                // Las tablas de V2 ya no existen.
                assertThat(scalar(statement, "SELECT to_regclass('public.drivers_old')")).isNull();
                assertThat(scalar(statement, "SELECT to_regclass('public.vehicles_old')")).isNull();
                assertThat(scalar(statement, "SELECT to_regclass('public.passengers_old')")).isNull();
            }
        }
    }

    // Datos con la forma de V2: drivers/passengers con uid de texto.
    private static void insertV2Data(Statement s) throws SQLException {
        s.execute("INSERT INTO drivers (uid, first_name, email, role, approval_status) "
                + "VALUES ('founder', 'Fundador', 'f@test.com', 'superuser', 'approved')");
        s.execute("INSERT INTO drivers (uid, first_name, email, role, approval_status, approved_by, "
                + "is_blocked, block_reason, blocked_by, language, fcm_token) "
                + "VALUES ('d1', 'Ana Conductora', 'ana@test.com', 'driver', 'approved', 'founder', "
                + "true, 'Queja', 'founder', 'es', 'tok-driver')");
        s.execute("INSERT INTO drivers (uid, first_name, role, approval_status) "
                + "VALUES ('d2', 'Luis', 'admin', 'pending')");

        // El vehículo apunta al conductor, y el conductor al vehículo.
        s.execute("INSERT INTO vehicles (vehicle_id, driver_uid, plate) VALUES ('v1', 'd1', 'PBX-1234')");
        s.execute("UPDATE drivers SET vehicle_id = 'v1' WHERE uid = 'd1'");

        // d1 también es pasajero (misma cuenta), con otro nombre y otro token.
        s.execute("INSERT INTO passengers (uid, first_name, language, fcm_token) "
                + "VALUES ('d1', 'Ana Pasajera', 'en', 'tok-passenger')");
        s.execute("INSERT INTO passengers (uid, first_name, email) VALUES ('p1', 'Eva', 'eva@test.com')");
    }

    private static int count(Statement statement, String table) throws SQLException {
        return Integer.parseInt(scalar(statement, "SELECT count(*) FROM " + table));
    }

    private static String scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }
}
