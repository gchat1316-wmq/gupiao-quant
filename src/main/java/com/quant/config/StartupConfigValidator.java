package com.quant.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

/**
 * Validates that secrets required in non-local profiles are non-empty.
 *
 * <p>Implemented as an {@link EnvironmentPostProcessor} so it runs DURING environment preparation,
 * before any bean is instantiated — meaning {@code @PostConstruct} checks in beans like {@code
 * JwtTokenProvider} never get the chance to throw a confusing wrapped stack trace. The app fails
 * immediately with a one-line, actionable error.
 *
 * <p>Local, test and the default (no profile) profile are exempt — devs and CI run without prod
 * secrets. Any explicitly activated non-local/test profile (e.g. {@code prod}) is treated as
 * production and must have all secrets configured.
 *
 * <p>To extend: add another required-secret check in the same fail-fast shape, then list this class
 * in {@code META-INF/spring.factories} under {@code
 * org.springframework.boot.env.EnvironmentPostProcessor} (already done).
 */
public class StartupConfigValidator implements EnvironmentPostProcessor {

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
    if (env.acceptsProfiles(Profiles.of("local"))) return;
    if (env.acceptsProfiles(Profiles.of("test"))) return;
    if (env.getActiveProfiles().length == 0) return;

    require(env, "spring.datasource.password", "DB_PASSWORD");
    require(env, "app.jwt.secret", "JWT_SECRET");
    require(env, "ai.minimax.api-key", "AI_MINIMAX_KEY");
    require(env, "ai.sensenova.api-key", "SENSENOVA_API_KEY or AI_SENSENOVA_KEY");
    require(env, "ai.tavily.api-key", "TAVILY_API_KEY");
    require(env, "prosperity-strong.tdx.api-key", "TDX_API_KEY");
    require(env, "notification.serverchan.send-key", "SERVER_CHAN_SEND_KEY");
    require(env, "notification.wish-pool.webhook-url", "WISH_POOL_FEISHU_WEBHOOK_URL");
  }

  private void require(ConfigurableEnvironment env, String key, String friendlyEnvVar) {
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
