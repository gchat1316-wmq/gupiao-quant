package com.quant.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.quant.dto.monitor.MonitorAddRequest;

class MonitorControllerParseCodesTest {

  @Test
  void parsesCommaAndNewlineBatch() {
    MonitorAddRequest req = new MonitorAddRequest();
    req.setStockCode("600519\n000001, 300750");
    List<String> codes = MonitorController.parseCodes(req);
    assertEquals(List.of("600519.SH", "000001.SZ", "300750.SZ"), codes);
  }

  @Test
  void mergesStockCodesList() {
    MonitorAddRequest req = new MonitorAddRequest();
    req.setStockCodes(List.of("sh600519", "002594.SZ"));
    req.setStockCode("600519"); // duplicate of first
    List<String> codes = MonitorController.parseCodes(req);
    assertEquals(2, codes.size());
    assertTrue(codes.contains("600519.SH"));
    assertTrue(codes.contains("002594.SZ"));
  }
}
