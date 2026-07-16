package com.quant.service.aistockdata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.quant.config.BaostockSyncProperties;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class BaostockSyncService {

  private static final String DAILY_UNIQUE_INDEX = "uk_trade_stock_daily_code_date";

  private final BaostockSyncProperties properties;
  private final DataSourceProperties dataSourceProperties;
  private final JdbcTemplate jdbcTemplate;
  private final CommandRunner commandRunner;
  private final AtomicBoolean running = new AtomicBoolean(false);

  @Autowired
  public BaostockSyncService(
      BaostockSyncProperties properties,
      DataSourceProperties dataSourceProperties,
      JdbcTemplate jdbcTemplate) {
    this(properties, dataSourceProperties, jdbcTemplate, new SystemCommandRunner());
  }

  BaostockSyncService(
      BaostockSyncProperties properties,
      DataSourceProperties dataSourceProperties,
      JdbcTemplate jdbcTemplate,
      CommandRunner commandRunner) {
    this.properties = properties;
    this.dataSourceProperties = dataSourceProperties;
    this.jdbcTemplate = jdbcTemplate;
    this.commandRunner = commandRunner;
  }

  public void syncNow(String reason, int daysBack) {
    if (!running.compareAndSet(false, true)) {
      log.info("BaoStock sync already running, skip trigger: {}", reason);
      return;
    }
    try {
      ensureDailyUniqueIndex();
      Map<String, String> env = buildDbEnv();
      Path workdir = Path.of("").toAbsolutePath();
      Duration timeout = Duration.ofSeconds(properties.getTimeoutSeconds());
      List<String> errors = new ArrayList<>();

      runStage(
          List.of(
              properties.getPythonCommand(),
              "scripts/baostock_daily_sync.py",
              "--days-back",
              String.valueOf(Math.max(daysBack, 1))),
          workdir,
          env,
          timeout,
          reason,
          "daily",
          errors);
      runStage(
          List.of(properties.getPythonCommand(), "scripts/baostock_basic_sync.py"),
          workdir,
          env,
          timeout,
          reason,
          "basic",
          errors);
      if (properties.isFinancialEnabled()) {
        runStage(
            List.of(
                properties.getPythonCommand(),
                "scripts/baostock_financial_sync.py",
                "--start-year",
                String.valueOf(properties.getFinancialStartYear())),
            workdir,
            env,
            timeout,
            reason,
            "financial",
            errors);
      }
      if (!errors.isEmpty()) {
        throw new IllegalStateException(String.join("; ", errors));
      }
    } finally {
      running.set(false);
    }
  }

  /** 仅触发财务同步（手动回填用），不跑 daily/basic。 */
  public void syncFinancialOnly(String reason) {
    if (!running.compareAndSet(false, true)) {
      log.info("BaoStock sync already running, skip trigger: {}", reason);
      return;
    }
    try {
      Map<String, String> env = buildDbEnv();
      Path workdir = Path.of("").toAbsolutePath();
      Duration timeout = Duration.ofSeconds(properties.getTimeoutSeconds());
      List<String> errors = new ArrayList<>();

      runStage(
          List.of(
              properties.getPythonCommand(),
              "scripts/baostock_financial_sync.py",
              "--start-year",
              String.valueOf(properties.getFinancialStartYear())),
          workdir,
          env,
          timeout,
          reason,
          "financial",
          errors);
      if (!errors.isEmpty()) {
        throw new IllegalStateException(String.join("; ", errors));
      }
    } finally {
      running.set(false);
    }
  }

  private void runStage(
      List<String> command,
      Path workdir,
      Map<String, String> env,
      Duration timeout,
      String reason,
      String stage,
      List<String> errors) {
    long startedAt = System.nanoTime();
    log.info(
        "BaoStock {} sync start, reason={}, command={}", stage, reason, String.join(" ", command));
    try {
      CommandResult result = commandRunner.run(command, workdir, env, timeout);
      if (result.exitCode() != 0) {
        throw new IllegalStateException(
            "BaoStock "
                + stage
                + " sync failed: "
                + firstNonBlank(result.stderr(), result.stdout(), "exit=" + result.exitCode()));
      }
      log.info(
          "BaoStock {} sync ok, reason={}, elapsed={}s, output={}",
          stage,
          reason,
          elapsedSeconds(startedAt),
          firstNonBlank(result.stdout(), "ok"));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      String message = "BaoStock " + stage + " sync interrupted";
      log.warn("{} reason={}, elapsed={}s", message, reason, elapsedSeconds(startedAt));
      errors.add(message);
    } catch (IOException e) {
      String message = "BaoStock " + stage + " sync unavailable: " + e.getMessage();
      log.warn("{} reason={}, elapsed={}s", message, reason, elapsedSeconds(startedAt));
      errors.add(message);
    } catch (RuntimeException e) {
      log.warn(
          "BaoStock {} sync failed, reason={}, elapsed={}s, err={}",
          stage,
          reason,
          elapsedSeconds(startedAt),
          e.getMessage());
      errors.add(e.getMessage());
    }
  }

  private void ensureDailyUniqueIndex() {
    Integer count =
        jdbcTemplate.queryForObject(
            """
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND index_name = ?
                """,
            Integer.class,
            "trade_stock_daily",
            DAILY_UNIQUE_INDEX);
    if (count != null && count > 0) {
      return;
    }
    jdbcTemplate.execute(
        """
                CREATE UNIQUE INDEX uk_trade_stock_daily_code_date
                ON trade_stock_daily (stock_code, trade_date)
                """);
    log.info("Created missing unique index: {}", DAILY_UNIQUE_INDEX);
  }

  private Map<String, String> buildDbEnv() {
    String url = dataSourceProperties.getUrl();
    if (url == null || !url.startsWith("jdbc:mysql://")) {
      throw new IllegalStateException("unsupported datasource url: " + url);
    }
    String body = url.substring("jdbc:mysql://".length());
    int slash = body.indexOf('/');
    if (slash < 0) {
      throw new IllegalStateException("invalid datasource url: " + url);
    }
    String hostPort = body.substring(0, slash);
    String dbPart = body.substring(slash + 1);
    int qmark = dbPart.indexOf('?');
    String dbName = qmark >= 0 ? dbPart.substring(0, qmark) : dbPart;
    String[] hp = hostPort.split(":", 2);
    String host = hp[0];
    String port = hp.length > 1 ? hp[1] : "3306";

    Map<String, String> env = new LinkedHashMap<>();
    env.put("DB_HOST", host);
    env.put("DB_PORT", port);
    env.put("DB_NAME", dbName);
    env.put("DB_USERNAME", firstNonBlank(dataSourceProperties.getUsername(), ""));
    env.put("DB_PASSWORD", firstNonBlank(dataSourceProperties.getPassword(), ""));
    return env;
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return "";
  }

  private long elapsedSeconds(long startedAt) {
    return Duration.ofNanos(System.nanoTime() - startedAt).toSeconds();
  }

  public interface CommandRunner {
    CommandResult run(List<String> command, Path workdir, Map<String, String> env, Duration timeout)
        throws IOException, InterruptedException;
  }

  public record CommandResult(int exitCode, String stdout, String stderr) {}

  static class SystemCommandRunner implements CommandRunner {
    @Override
    public CommandResult run(
        List<String> command, Path workdir, Map<String, String> env, Duration timeout)
        throws IOException, InterruptedException {
      ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(command));
      pb.directory(workdir.toFile());
      pb.environment().putAll(env);
      Process process = pb.start();
      boolean done = process.waitFor(timeout.toSeconds(), java.util.concurrent.TimeUnit.SECONDS);
      if (!done) {
        process.destroyForcibly();
        return new CommandResult(124, "", "timeout after " + timeout.toSeconds() + "s");
      }
      String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      return new CommandResult(process.exitValue(), stdout, stderr);
    }
  }
}
