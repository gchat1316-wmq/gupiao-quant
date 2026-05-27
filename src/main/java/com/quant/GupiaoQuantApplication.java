package com.quant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GupiaoQuantApplication {

    public static void main(String[] args) {
        SpringApplication.run(GupiaoQuantApplication.class, args);
    }
}
