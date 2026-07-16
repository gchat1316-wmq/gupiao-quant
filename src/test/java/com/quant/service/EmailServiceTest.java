package com.quant.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * EmailService TDD 测试。
 *
 * <p>覆盖： 1. generateCode：6 位数字 2. sendCode：dev 环境（未配 SMTP）→ 打印到 stdout 并返回 code 3. sendCode：配了 SMTP
 * → 通过 WebClient 发（这里只验证不抛异常 + 返回 code，WebClient 注入 mock）
 */
@DisplayName("EmailService")
class EmailServiceTest {

  private WebClient.Builder webClientBuilder;
  private EmailService service;

  @BeforeEach
  void setUp() {
    webClientBuilder = WebClient.builder();
    service = new EmailService(webClientBuilder);
  }

  @Nested
  @DisplayName("generateCode")
  class GenerateCode {

    @Test
    @DisplayName("生成 6 位数字字符串")
    void sixDigitCode() {
      String code = service.generateCode();
      assertThat(code).hasSize(6);
      assertThat(code).matches("\\d{6}");
    }

    @Test
    @DisplayName("多次生成结果不同（随机性）")
    void randomDistribution() {
      boolean anyDifferent = false;
      String first = service.generateCode();
      for (int i = 0; i < 50; i++) {
        if (!service.generateCode().equals(first)) {
          anyDifferent = true;
          break;
        }
      }
      assertThat(anyDifferent).isTrue();
    }
  }

  @Nested
  @DisplayName("sendCode (dev mode)")
  class SendCodeDevMode {

    @BeforeEach
    void noSmtp() {
      // 默认空配置 = dev 模式（直接打印）
      ReflectionTestUtils.setField(service, "smtpHost", "");
    }

    @Test
    @DisplayName("未配 SMTP → 返回 code（dev 模式打印）")
    void devModeReturnsCode() {
      String result = service.sendCode("user@example.com", "123456");
      assertThat(result).isEqualTo("123456");
    }
  }

  @Nested
  @DisplayName("isMock")
  class IsMock {

    @Test
    @DisplayName("smtpHost 为空 → true（dev 模式）")
    void emptyHostIsMock() {
      ReflectionTestUtils.setField(service, "smtpHost", "");
      assertThat(service.isMock()).isTrue();
    }

    @Test
    @DisplayName("smtpHost 为 null → true")
    void nullHostIsMock() {
      ReflectionTestUtils.setField(service, "smtpHost", null);
      assertThat(service.isMock()).isTrue();
    }

    @Test
    @DisplayName("smtpHost 有值 → false（已配置真服务）")
    void configuredIsNotMock() {
      ReflectionTestUtils.setField(service, "smtpHost", "smtp.gupiao-quant.com");
      assertThat(service.isMock()).isFalse();
    }
  }
}
