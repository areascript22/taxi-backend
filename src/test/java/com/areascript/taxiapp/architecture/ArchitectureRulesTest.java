package com.areascript.taxiapp.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

// Reglas de la arquitectura objetivo del servidor: paquete por funcionalidad
// (ride/, chat/, account/, driveradmin/, notification/, common/) y, dentro de
// cada una, capas api -> application -> domain <- infra.
//
// Fase 0: las capas todavía no existen, así que las reglas sobre ellas pasan
// "en vacío" (allowEmptyShould). Empiezan a morder solas a medida que cada
// feature se migra, sin tener que volver a tocar este archivo.
class ArchitectureRulesTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.areascript.taxiapp");
    }

    // El dominio es Java puro: ni Firebase, ni Spring, ni servlets.
    @Test
    void el_dominio_no_depende_de_frameworks_ni_de_firebase() {
        ArchRule rule = noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.google.firebase..",
                        "com.google.cloud..",
                        "org.springframework..",
                        "jakarta.."
                )
                .allowEmptyShould(true);
        rule.check(classes);
    }

    // Los casos de uso hablan con interfaces del dominio, no con el SDK.
    @Test
    void la_capa_de_aplicacion_no_usa_el_sdk_de_firebase() {
        ArchRule rule = noClasses().that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.google.firebase..",
                        "com.google.cloud.."
                )
                .allowEmptyShould(true);
        rule.check(classes);
    }

    // La inversión de dependencias: domain y application declaran lo que
    // necesitan (interfaces) y infra lo implementa. Nunca al revés.
    @Test
    void domain_y_application_no_dependen_de_infra() {
        ArchRule rule = noClasses().that().resideInAnyPackage("..domain..", "..application..")
                .should().dependOnClassesThat().resideInAPackage("..infra..")
                .allowEmptyShould(true);
        rule.check(classes);
    }

    // Los controllers llegan a la infraestructura solo a través de los casos de uso.
    @Test
    void la_api_no_depende_de_la_infraestructura() {
        ArchRule rule = noClasses().that().resideInAPackage("..api..")
                .should().dependOnClassesThat().resideInAPackage("..infra..")
                .allowEmptyShould(true);
        rule.check(classes);
    }

    // Ya se cumple desde la fase 2 y no debe retroceder: el token de Firebase
    // lo lee solo common.security, que lo convierte en AuthenticatedUser.
    // Controllers y services trabajan con la identidad ya resuelta.
    @Test
    void el_token_de_firebase_solo_se_usa_en_common_security() {
        ArchRule rule = noClasses().that().resideOutsideOfPackage("..common.security..")
                .should().dependOnClassesThat().haveFullyQualifiedName("com.google.firebase.auth.FirebaseToken");
        rule.check(classes);
    }

    // Ya se cumple hoy y no debe retroceder: ningún controller lee o escribe
    // directo en Realtime Database ni en Firestore.
    @Test
    void ningun_controller_accede_directo_a_las_bases_de_datos() {
        ArchRule rule = noClasses().that().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.google.firebase.database..",
                        "com.google.cloud.firestore.."
                );
        rule.check(classes);
    }
}
