package com.quant.config;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.quant.entity.User;
import com.quant.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 首次启动自举 ADMIN 用户 — 必须在 Flyway 完成所有 V__ migrations 之后跑（@Order(2)，Flyway 是 @Order(0)）。 任何既有用户的 boot
 * 都不会触发（仅当 userRepository.count() == 0 时）。 DDL 拆分见 SchemaInitializer 删除 commit +
 * src/main/resources/db/migration/V*.sql。
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class FirstAdminBootstrap implements ApplicationRunner {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (userRepository.count() > 0) {
      boolean hasEnabledAdmin =
          userRepository.findAll().stream()
              .anyMatch(
                  u -> u.getRole() == User.Role.ADMIN && !Boolean.TRUE.equals(u.getDisabled()));
      if (!hasEnabledAdmin) {
        log.warn("【安全恢复】未发现可用管理员，将重置现有 ADMIN 账号...");
        userRepository.findAll().stream()
            .filter(u -> u.getRole() == User.Role.ADMIN)
            .forEach(
                u -> {
                  u.setDisabled(false);
                  userRepository.save(u);
                });
        log.warn("【安全恢复】ADMIN 账号已恢复可用，请立即登录并检查安全设置。");
      }
      return;
    }

    String rawPassword = generateSecurePassword(16);
    User admin = new User();
    admin.setUsername("admin");
    admin.setRole(User.Role.ADMIN);
    admin.setPasswordHash(passwordEncoder.encode(rawPassword));
    userRepository.save(admin);

    log.warn("═══════════════════════════════════════════════════════");
    log.warn("【首次启动】系统已自动创建管理员账号：");
    log.warn("  用户名：admin");
    log.warn("  密码：{}", rawPassword);
    log.warn("请立即登录并修改密码！");
    log.warn("═══════════════════════════════════════════════════════");
  }

  private String generateSecurePassword(int length) {
    byte[] bytes = new byte[length];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(bytes)
        .replace("-", "")
        .replace("_", "")
        .substring(0, length);
  }
}
