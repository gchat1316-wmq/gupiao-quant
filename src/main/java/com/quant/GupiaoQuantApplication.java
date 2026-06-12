package com.quant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.quant.config.AiProperties;
import com.quant.config.BaostockSyncProperties;
import com.quant.config.NotificationProperties;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({AiProperties.class, NotificationProperties.class, BaostockSyncProperties.class})
public class GupiaoQuantApplication {

    public static void main(String[] args) {
        SpringApplication.run(GupiaoQuantApplication.class, args);
    }
}
