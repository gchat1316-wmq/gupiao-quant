package com.quant;

import com.quant.config.AiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AiProperties.class)
public class GupiaoQuantApplication {

    public static void main(String[] args) {
        SpringApplication.run(GupiaoQuantApplication.class, args);
    }
}
