package com.ivanchesa.trainwatch;

import org.springframework.boot.SpringApplication;

public class TestTrainwatchBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(TrainwatchBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
