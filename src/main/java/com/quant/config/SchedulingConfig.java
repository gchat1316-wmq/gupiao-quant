package com.quant.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables {@code @Scheduled} cron jobs. Excluded from the {@code test} profile because (a) tests
 * don't depend on cron execution and (b) some scheduled jobs run heavy work (Python scripts, real
 * HTTP calls) that pollute test logs and slow test suites down.
 *
 * <p>Production profiles (default, prod) keep cron enabled; {@code local} keeps cron enabled so
 * devs can observe job behavior.
 */
@Configuration
@EnableScheduling
@Profile("!test")
public class SchedulingConfig {}
