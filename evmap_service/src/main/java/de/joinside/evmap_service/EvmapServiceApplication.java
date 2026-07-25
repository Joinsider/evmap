package de.joinside.evmap_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EvmapServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(EvmapServiceApplication.class, args);
    }

}
