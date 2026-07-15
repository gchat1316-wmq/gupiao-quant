package com.quant.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.junit.jupiter.api.Assertions.assertThrows;

class StartupConfigValidatorTest {

    @Test
    void failsWhenJwtSecretBlankInProdProfile() {
        // minimal context — only SecurityConfig + our validator + a placeholder AuthProperties
        // (the real production app uses @SpringBootApplication; we narrow to surface the guard)
        SpringApplicationBuilder builder = new SpringApplicationBuilder(StartupConfigValidator.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.profiles.active=prod",
                        "spring.main.web-application-type=none",
                        "spring.autoconfigure.exclude=" +
                                "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration",
                        "app.jwt.secret=");
        assertThrows(IllegalStateException.class, builder::run);
    }
}