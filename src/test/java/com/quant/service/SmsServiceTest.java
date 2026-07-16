package com.quant.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * SmsService TDD 测试。
 *
 * <p>覆盖： 1. generateCode：6 位数字 2. sendCode：dev 环境（未配华信）→ 打印到 stdout 并返回 code 3. isMock：未配置服务商时
 * true；配置了 username 时 false（用于 AuthController 决定是否回传 code 给前端）
 */
@DisplayName("SmsService")
class SmsServiceTest {

  private SmsService service;

  @BeforeEach
  void setUp() {
    service = new SmsService();
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
      String first = service.generateCode();
      boolean anyDifferent = false;
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
    void noProvider() {
      // 默认空配置 = dev 模式（直接打印）
      ReflectionTestUtils.setField(service, "username", "");
    }

    @Test
    @DisplayName("未配华信 → 返回 code（dev 模式打印）")
    void devModeReturnsCode() {
      String result = service.sendCode("13800138000", "123456");
      assertThat(result).isEqualTo("123456");
    }
  }

  @Nested
  @DisplayName("isMock")
  class IsMock {

    @Test
    @DisplayName("username 为空 → true（dev 模式）")
    void emptyUsernameIsMock() {
      ReflectionTestUtils.setField(service, "username", "");
      assertThat(service.isMock()).isTrue();
    }

    @Test
    @DisplayName("username 为 null → true")
    void nullUsernameIsMock() {
      ReflectionTestUtils.setField(service, "username", null);
      assertThat(service.isMock()).isTrue();
    }

    @Test
    @DisplayName("username 有值 → false（已配置真服务）")
    void configuredIsNotMock() {
      ReflectionTestUtils.setField(service, "username", "huaxin-user");
      assertThat(service.isMock()).isFalse();
    }
  }
}
