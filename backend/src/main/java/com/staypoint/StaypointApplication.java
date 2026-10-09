package com.staypoint;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class StaypointApplication {

	public static void main(String[] args) {
		SpringApplication.run(StaypointApplication.class, args);
	}

}
