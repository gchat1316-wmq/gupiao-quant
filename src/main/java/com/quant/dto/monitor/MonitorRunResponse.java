package com.quant.dto.monitor;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class MonitorRunResponse {
  private String message;
  private int triggered;
}
