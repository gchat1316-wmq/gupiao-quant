package com.quant.prosperitystrong;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.quant.service.prosperitystrong.MainlineEvaluator;

class MainlineEvaluatorTest {

  private final MainlineEvaluator evaluator = new MainlineEvaluator();

  @Test
  void netMarginNear25_getsHighestScore() {
    MainlineEvaluator.Score sNear25 =
        evaluator.evaluate(new BigDecimal("80"), new BigDecimal("25"), new BigDecimal("80"));
    MainlineEvaluator.Score sFar =
        evaluator.evaluate(new BigDecimal("80"), new BigDecimal("5"), new BigDecimal("80"));

    assertTrue(sNear25.mainlineScore().compareTo(sFar.mainlineScore()) > 0, "净利率接近25%的评分应高于偏离较大的");
  }

  @Test
  void mainBizRatioBelow30_zeroesMainlineComponent() {
    MainlineEvaluator.Score low =
        evaluator.evaluate(new BigDecimal("20"), new BigDecimal("25"), new BigDecimal("80"));
    assertFalse(low.mainlinePassed());
  }

  @Test
  void mainBizRatioAbove50_marksMainlinePassed() {
    MainlineEvaluator.Score ok =
        evaluator.evaluate(new BigDecimal("60"), new BigDecimal("22"), new BigDecimal("80"));
    assertTrue(ok.mainlinePassed());
  }

  @Test
  void nullInputsUseDefaults_andStillProduceScore() {
    MainlineEvaluator.Score s = evaluator.evaluate(null, null, null);
    assertNotNull(s.mainlineScore());
    assertTrue(s.mainlineScore().compareTo(BigDecimal.ZERO) >= 0);
  }
}
