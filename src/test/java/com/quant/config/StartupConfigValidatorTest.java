package com.quant.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

/**
 * Unit tests for {@link StartupConfigValidator}. Validates the validator's fail-fast contract
 * directly — without spinning up a full Spring context — so the test stays fast and unambiguous
 * about which key triggered the failure.
 */
@DisplayName("StartupConfigValidator")
class StartupConfigValidatorTest {

  private final StartupConfigValidator validator = new StartupConfigValidator();
  private final SpringApplication app = new SpringApplication();

  @Test
  @DisplayName("prod profile + missing JWT_SECRET → throws with clear message naming JWT_SECRET")
  void prodMissingJwtSecretThrows() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("prod");
    // spring.datasource.password + other keys not set either; JWT_SECRET should be flagged first
    // because that's how the require() list is ordered.
    env.setProperty("spring.datasource.password", "x");
    env.setProperty("ai.minimax.api-key", "x");
    env.setProperty("ai.sensenova.api-key", "x");
    env.setProperty("ai.tavily.api-key", "x");
    env.setProperty("prosperity-strong.tdx.api-key", "x");
    env.setProperty("notification.serverchan.send-key", "x");
    env.setProperty("notification.wish-pool.webhook-url", "x");

    assertThatThrownBy(() -> validator.postProcessEnvironment(env, app))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.jwt.secret")
        .hasMessageContaining("JWT_SECRET");
  }

  @Test
  @DisplayName("prod profile + missing DB_PASSWORD → throws first, naming DB_PASSWORD")
  void prodMissingDbPasswordThrows() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("prod");

    assertThatThrownBy(() -> validator.postProcessEnvironment(env, app))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("spring.datasource.password")
        .hasMessageContaining("DB_PASSWORD");
  }

  @Test
  @DisplayName("local profile + missing JWT_SECRET → bypassed (devs may run without prod secrets)")
  void localMissingJwtSecretDoesNotThrow() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("local");
    // app.jwt.secret unset on purpose

    validator.postProcessEnvironment(env, app); // must not throw

    assertThat(env.getActiveProfiles()).contains("local");
  }

  @Test
  @DisplayName("test profile + missing JWT_SECRET → bypassed (CI may run without prod secrets)")
  void testMissingJwtSecretDoesNotThrow() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("test");

    validator.postProcessEnvironment(env, app);

    assertThat(env.getActiveProfiles()).contains("test");
  }

  @Test
  @DisplayName("default profile (no profile active) + missing JWT_SECRET → bypassed")
  void defaultProfileMissingJwtSecretDoesNotThrow() {
    MockEnvironment env = new MockEnvironment();
    // no active profiles

    validator.postProcessEnvironment(env, app);

    assertThat(env.getActiveProfiles()).isEmpty();
  }

  @Test
  @DisplayName("prod profile + all required secrets present → does not throw")
  void prodAllSecretsPresentDoesNotThrow() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("prod");
    env.setProperty("spring.datasource.password", "x");
    env.setProperty("app.jwt.secret", "x");
    env.setProperty("ai.minimax.api-key", "x");
    env.setProperty("ai.sensenova.api-key", "x");
    env.setProperty("ai.tavily.api-key", "x");
    env.setProperty("prosperity-strong.tdx.api-key", "x");
    env.setProperty("notification.serverchan.send-key", "x");
    env.setProperty("notification.wish-pool.webhook-url", "x");

    validator.postProcessEnvironment(env, app); // must not throw
  }

  @Test
  @DisplayName("empty env var (resolved to blank string) → still treated as missing")
  void blankSecretIsMissing() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("prod");
    env.setProperty("spring.datasource.password", "x");
    env.setProperty("app.jwt.secret", "   "); // blank
    env.setProperty("ai.minimax.api-key", "x");
    env.setProperty("ai.sensenova.api-key", "x");
    env.setProperty("ai.tavily.api-key", "x");
    env.setProperty("prosperity-strong.tdx.api-key", "x");
    env.setProperty("notification.serverchan.send-key", "x");
    env.setProperty("notification.wish-pool.webhook-url", "x");

    assertThatThrownBy(() -> validator.postProcessEnvironment(env, app))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.jwt.secret");
  }
}
