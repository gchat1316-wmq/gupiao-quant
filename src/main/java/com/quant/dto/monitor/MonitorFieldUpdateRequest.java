package com.quant.dto.monitor;

import lombok.Data;

/** PATCH /api/monitor/pool/{code}/{poolType}/field 的请求体。 */
@Data
public class MonitorFieldUpdateRequest {
  private String field;
  private Object value;
}
