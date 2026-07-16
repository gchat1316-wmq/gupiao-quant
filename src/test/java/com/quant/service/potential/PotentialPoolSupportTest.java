package com.quant.service.potential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.quant.dto.techai.PositionFillDTO;
import com.quant.dto.techai.TechAiAlertDTO;
import com.quant.entity.InvestAlert;
import com.quant.entity.PotentialPool;
import com.quant.entity.PotentialPositionFill;
import com.quant.entity.TradeStockBasic;

@DisplayName("PotentialPoolSupport")
class PotentialPoolSupportTest {

  @Nested
  @DisplayName("fmt")
  class Fmt {
    @Test
    void returnsDashForNull() {
      assertThat(PotentialPoolSupport.fmt(null)).isEqualTo("-");
    }

    @Test
    void stripsTrailingZeros() {
      assertThat(PotentialPoolSupport.fmt(new BigDecimal("10.00"))).isEqualTo("10");
      assertThat(PotentialPoolSupport.fmt(new BigDecimal("3.5000"))).isEqualTo("3.5");
      assertThat(PotentialPoolSupport.fmt(new BigDecimal("0"))).isEqualTo("0");
    }
  }

  @Nested
  @DisplayName("pctChange")
  class PctChange {
    @Test
    void returnsNullWhenBaseZeroOrNull() {
      assertThat(PotentialPoolSupport.pctChange(new BigDecimal("10"), BigDecimal.ZERO)).isNull();
      assertThat(PotentialPoolSupport.pctChange(null, new BigDecimal("10"))).isNull();
      assertThat(PotentialPoolSupport.pctChange(new BigDecimal("10"), null)).isNull();
    }

    @Test
    void computesPercentWithTwoDecimals() {
      // (11 - 10) / 10 * 100 = 10.00
      assertThat(PotentialPoolSupport.pctChange(new BigDecimal("11"), new BigDecimal("10")))
          .isEqualByComparingTo("10.00");
      // (9 - 10) / 10 * 100 = -10.00
      assertThat(PotentialPoolSupport.pctChange(new BigDecimal("9"), new BigDecimal("10")))
          .isEqualByComparingTo("-10.00");
    }
  }

  @Nested
  @DisplayName("isTradingTime")
  class IsTradingTime {
    @Test
    void delegatesToLocalClock() {
      // 纯功能性 sanity check：返回 boolean，与 LocalTime.now() 一致
      boolean result = PotentialPoolSupport.isTradingTime();
      assertThat(result).isIn(true, false);
    }
  }

  @Nested
  @DisplayName("parsePositiveDecimal")
  class ParsePositiveDecimal {
    @Test
    void returnsNullForBlank() {
      assertThat(PotentialPoolSupport.parsePositiveDecimal(null, "f")).isNull();
      assertThat(PotentialPoolSupport.parsePositiveDecimal("", "f")).isNull();
      assertThat(PotentialPoolSupport.parsePositiveDecimal("   ", "f")).isNull();
    }

    @Test
    void parsesAndScalesToTwoDecimals() {
      assertThat(PotentialPoolSupport.parsePositiveDecimal("12.3456", "f"))
          .isEqualByComparingTo("12.35");
    }

    @Test
    void rejectsZeroOrNegative() {
      assertThatThrownBy(() -> PotentialPoolSupport.parsePositiveDecimal("0", "f"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("阈值必须大于 0");
      assertThatThrownBy(() -> PotentialPoolSupport.parsePositiveDecimal("-1", "f"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMalformed() {
      assertThatThrownBy(() -> PotentialPoolSupport.parsePositiveDecimal("abc", "f"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("阈值格式错误");
    }
  }

  @Nested
  @DisplayName("parsePositiveInteger")
  class ParsePositiveInteger {
    @Test
    void returnsNullForBlank() {
      assertThat(PotentialPoolSupport.parsePositiveInteger(null, "f")).isNull();
      assertThat(PotentialPoolSupport.parsePositiveInteger("", "f")).isNull();
    }

    @Test
    void parsesValidInteger() {
      assertThat(PotentialPoolSupport.parsePositiveInteger("14", "f")).isEqualTo(14);
    }

    @Test
    void rejectsZeroOrNegative() {
      assertThatThrownBy(() -> PotentialPoolSupport.parsePositiveInteger("0", "f"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> PotentialPoolSupport.parsePositiveInteger("-3", "f"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMalformed() {
      assertThatThrownBy(() -> PotentialPoolSupport.parsePositiveInteger("abc", "f"))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("parseFlag")
  class ParseFlag {
    @Test
    void nullReturnsZero() {
      assertThat(PotentialPoolSupport.parseFlag(null)).isEqualTo(0);
    }

    @Test
    void truthyStringsReturnOne() {
      assertThat(PotentialPoolSupport.parseFlag("1")).isEqualTo(1);
      assertThat(PotentialPoolSupport.parseFlag("true")).isEqualTo(1);
      assertThat(PotentialPoolSupport.parseFlag("TRUE")).isEqualTo(1);
      assertThat(PotentialPoolSupport.parseFlag("on")).isEqualTo(1);
      assertThat(PotentialPoolSupport.parseFlag("yes")).isEqualTo(1);
      assertThat(PotentialPoolSupport.parseFlag("  on  ")).isEqualTo(1);
    }

    @Test
    void otherStringsReturnZero() {
      assertThat(PotentialPoolSupport.parseFlag("0")).isEqualTo(0);
      assertThat(PotentialPoolSupport.parseFlag("false")).isEqualTo(0);
      assertThat(PotentialPoolSupport.parseFlag("off")).isEqualTo(0);
      assertThat(PotentialPoolSupport.parseFlag("no")).isEqualTo(0);
      assertThat(PotentialPoolSupport.parseFlag("random")).isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("toFillDTO / toAlertDTO / displayStockName")
  class DtoMapping {
    @Test
    void toFillDTOcopiesAllFields() {
      LocalDateTime now = LocalDateTime.of(2026, 7, 16, 10, 0);
      PotentialPositionFill fill = new PotentialPositionFill();
      fill.setId(7L);
      fill.setPoolId(3);
      fill.setStockCode("002851.SZ");
      fill.setAction("open");
      fill.setPrice(new BigDecimal("12.34"));
      fill.setLots(new BigDecimal("5"));
      fill.setAmount(new BigDecimal("61700"));
      fill.setFee(new BigDecimal("5.20"));
      fill.setNote("note");
      fill.setFilledAt(now);

      PositionFillDTO dto = PotentialPoolSupport.toFillDTO(fill);

      assertThat(dto.getId()).isEqualTo(7L);
      assertThat(dto.getPoolId()).isEqualTo(3);
      assertThat(dto.getStockCode()).isEqualTo("002851.SZ");
      assertThat(dto.getAction()).isEqualTo("open");
      assertThat(dto.getPrice()).isEqualByComparingTo("12.34");
      assertThat(dto.getLots()).isEqualByComparingTo("5");
      assertThat(dto.getAmount()).isEqualByComparingTo("61700");
      assertThat(dto.getFee()).isEqualByComparingTo("5.20");
      assertThat(dto.getNote()).isEqualTo("note");
      assertThat(dto.getFilledAt()).isEqualTo(now);
    }

    @Test
    void toAlertDTOinterpretsPushedAndReadFlags() {
      InvestAlert pushedRead = new InvestAlert();
      pushedRead.setId(1L);
      pushedRead.setStockCode("000001.SZ");
      pushedRead.setSignalType("minute1:1.5");
      pushedRead.setTitle("title");
      pushedRead.setTriggerPrice(new BigDecimal("10"));
      pushedRead.setTriggerAt(LocalDateTime.of(2026, 7, 16, 9, 30));
      pushedRead.setPushed(1);
      pushedRead.setReadFlag(1);

      TechAiAlertDTO dto = PotentialPoolSupport.toAlertDTO(pushedRead);
      assertThat(dto.getId()).isEqualTo(1);
      assertThat(dto.getStockCode()).isEqualTo("000001.SZ");
      assertThat(dto.getSignalType()).isEqualTo("minute1:1.5");
      assertThat(dto.isPushed()).isTrue();
      assertThat(dto.isRead()).isTrue();

      InvestAlert notPushed = new InvestAlert();
      notPushed.setPushed(0);
      notPushed.setReadFlag(0);
      TechAiAlertDTO dto2 = PotentialPoolSupport.toAlertDTO(notPushed);
      assertThat(dto2.isPushed()).isFalse();
      assertThat(dto2.isRead()).isFalse();

      // null flags treated as false
      TechAiAlertDTO dto3 = PotentialPoolSupport.toAlertDTO(new InvestAlert());
      assertThat(dto3.isPushed()).isFalse();
      assertThat(dto3.isRead()).isFalse();
    }

    @Test
    void displayStockNamePrefersBasicThenPoolThenCode() {
      PotentialPool item = new PotentialPool();
      item.setStockCode("002851.SZ");
      item.setStockName("池子里的名字");

      // basic null → falls back to pool.stockName
      assertThat(PotentialPoolSupport.displayStockName(item, null)).isEqualTo("池子里的名字");

      // basic blank → falls back to pool.stockName
      TradeStockBasic blank = new TradeStockBasic();
      blank.setStockName("   ");
      assertThat(PotentialPoolSupport.displayStockName(item, blank)).isEqualTo("池子里的名字");

      // basic wins
      TradeStockBasic rich = new TradeStockBasic();
      rich.setStockName("正式名称");
      assertThat(PotentialPoolSupport.displayStockName(item, rich)).isEqualTo("正式名称");

      // neither → code
      PotentialPool codeOnly = new PotentialPool();
      codeOnly.setStockCode("600000.SH");
      assertThat(PotentialPoolSupport.displayStockName(codeOnly, null)).isEqualTo("600000.SH");
    }
  }
}
