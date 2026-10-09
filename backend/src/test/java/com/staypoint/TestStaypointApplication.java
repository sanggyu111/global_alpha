package com.staypoint;

import org.springframework.boot.SpringApplication;

public class TestStaypointApplication {

	public static void main(String[] args) {
		SpringApplication.from(StaypointApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
