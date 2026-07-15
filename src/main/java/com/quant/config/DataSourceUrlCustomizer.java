package com.quant.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * Rewrites spring.datasource.url to enable SSL when DB_USE_SSL=true.
 * Idempotent — does nothing in local/dev/test where DB_USE_SSL unset.
 *
 * MySQL server MUST have SSL enabled for useSSL=true to connect.
 * See docs/superpowers/plans/2026-07-15-secret-inventory.md.
 */
public class DataSourceUrlCustomizer implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        boolean sslOn = Boolean.parseBoolean(env.getProperty("DB_USE_SSL", "false"));
        if (!sslOn) return;

        String currentUrl = env.getProperty("spring.datasource.url");
        if (currentUrl == null || currentUrl.isBlank() || currentUrl.contains("useSSL=true")) return;

        String rewritten = currentUrl
                .replace("useSSL=false", "useSSL=true&requireSSL=true")
                .replace("allowPublicKeyRetrieval=true&", "")
                .replace("&allowPublicKeyRetrieval=true", "")
                .replace("?allowPublicKeyRetrieval=true&", "?");

        Map<String, Object> map = new HashMap<>();
        map.put("spring.datasource.url", rewritten);
        env.getPropertySources().addFirst(new MapPropertySource("dbSslCustomizer", map));
    }
}