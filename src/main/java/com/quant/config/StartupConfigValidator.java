package com.quant.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Validates that secrets required in non-local profiles are non-empty. Runs at @Order(0) so it
 * fails the app boot before anything else binds.
 *
 * <p>Local profile is exempt — devs run without prod secrets.
 *
 * <p>To extend: add another required-secret check in the same fail-fast shape.
 */
@Component
@org.springframework.core.annotation.Order(0)
@RequiredArgsConstructor
public class StartupConfigValidator implements ApplicationRunner {

  private final Environment env;

  @Override
  public void run(ApplicationArguments args) {
    if (env.acceptsProfiles(Profiles.of("local"))) return;
    if (env.acceptsProfiles(Profiles.of("test"))) return;

    require("spring.datasource.password", "DB_PASSWORD");
    require("app.jwt.secret", "JWT_SECRET");
    require("ai.minimax.api-key", "AI_MINIMAX_KEY");
    require("ai.sensenova.api-key", "SENSENOVA_API_KEY or AI_SENSENOVA_KEY");
    require("ai.tavily.api-key", "TAVILY_API_KEY");
    require("prosperity-strong.tdx.api-key", "TDX_API_KEY");
    require("notification.serverchan.send-key", "SERVER_CHAN_SEND_KEY");
    require("notification.wish-pool.webhook-url", "WISH_POOL_FEISHU_WEBHOOK_URL");
  }

  private void require(String key, String friendlyEnvVar) {
    String value = env.getProperty(key, "");
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          "Required configuration is missing or empty: "
              + key
              + " (set env var "
              + friendlyEnvVar
              + "). "
              + "Refusing to start in profile '"
              + String.join(",", env.getActiveProfiles())
              + "'.");
    }
  }
}
