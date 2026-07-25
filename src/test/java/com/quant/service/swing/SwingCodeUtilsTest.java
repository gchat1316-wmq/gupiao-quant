package com.quant.service.swing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SwingCodeUtilsTest {

  @Test
  void normalizeAddsExchangeSuffix() {
    assertThat(SwingCodeUtils.normalize("600519")).isEqualTo("600519.SH");
    assertThat(SwingCodeUtils.normalize("000001")).isEqualTo("000001.SZ");
    assertThat(SwingCodeUtils.normalize("300750.sz")).isEqualTo("300750.SZ");
    assertThat(SwingCodeUtils.normalize("688981.SH")).isEqualTo("688981.SH");
  }

  @Test
  void bareCodeStripsSuffix() {
    assertThat(SwingCodeUtils.bareCode("600519.SH")).isEqualTo("600519");
  }
}
