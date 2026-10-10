package com.areascript.taxiapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// @ConfigurationPropertiesScan registra los records @ConfigurationProperties
// (TaxiappProperties, FirebaseProperties) sin tener que listarlos a mano.
@SpringBootApplication
@ConfigurationPropertiesScan
public class TaxiappApplication {

	public static void main(String[] args) {
		SpringApplication.run(TaxiappApplication.class, args);
	}

}
