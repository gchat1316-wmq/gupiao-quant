ALTER TABLE `stock_analysis_record`
  ADD COLUMN `source_payload_json` LONGTEXT NULL COMMENT '统一多源原始数据包 JSON' AFTER `result_json`,
  ADD COLUMN `report_html` LONGTEXT NULL COMMENT '统一富报告 HTML' AFTER `source_payload_json`;
