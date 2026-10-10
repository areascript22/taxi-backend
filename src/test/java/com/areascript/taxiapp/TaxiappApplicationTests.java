package com.areascript.taxiapp;

import com.areascript.taxiapp.characterization.support.CharacterizationTestBase;
import org.junit.jupiter.api.Test;

// Usa la misma base que los tests de caracterización: el contexto se levanta
// con Firebase simulado. Antes se conectaba al proyecto Firebase de dev real
// (y corría SuperUserBootstrap contra él) en cada `mvn test`.
class TaxiappApplicationTests extends CharacterizationTestBase {

	@Test
	void contextLoads() {
	}

}
