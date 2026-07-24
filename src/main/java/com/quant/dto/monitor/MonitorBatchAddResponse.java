package com.quant.dto.monitor;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

@Data
public class MonitorBatchAddResponse {
  private boolean ok = true;
  private int added;
  private int skipped;
  private int failed;
  private List<Item> items = new ArrayList<>();

  @Data
  public static class Item {
    private String stockCode;
    private String poolType;
    private String status; // added | exists | failed
    private String message;
  }
}
