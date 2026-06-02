package com.quant.techai;

import com.quant.service.techai.TechAiStockCodeUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TechAiStockCodeUtils")
class TechAiStockCodeUtilsTest {

    @Test
    @DisplayName("normalizes project stock codes to lowercase suffix format")
    void normalizesToProjectCode() {
        assertThat(TechAiStockCodeUtils.normalizeProjectCode("300733")).isEqualTo("300733.sz");
        assertThat(TechAiStockCodeUtils.normalizeProjectCode("300733.SZ")).isEqualTo("300733.sz");
        assertThat(TechAiStockCodeUtils.normalizeProjectCode("688610.SH")).isEqualTo("688610.sh");
    }

    @Test
    @DisplayName("converts project stock codes to QMT code.market format")
    void convertsToQmtCode() {
        assertThat(TechAiStockCodeUtils.toQmtCode("300733.sz")).isEqualTo("300733.SZ");
        assertThat(TechAiStockCodeUtils.toQmtCode("688610.sh")).isEqualTo("688610.SH");
    }
}
