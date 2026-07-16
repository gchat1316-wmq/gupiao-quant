package com.quant.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.quant.dto.invest.ProsperityPickRecentDTO;
import com.quant.dto.invest.ProsperityPickResultDTO;
import com.quant.service.ProsperityPickService;

@RestController
@RequestMapping("/api/invest/prosperity-pick")
@CrossOrigin(origins = "*")
public class ProsperityPickController {

  private final ProsperityPickService service;

  public ProsperityPickController(ProsperityPickService service) {
    this.service = service;
  }

  /** 景气度选股 · 个股全维度分析（同日缓存，force=true 强制刷新） */
  @GetMapping
  public ProsperityPickResultDTO analyze(
      @RequestParam("keyword") String keyword,
      @RequestParam(value = "force", required = false, defaultValue = "false") boolean force) {
    return service.analyze(keyword, force);
  }

  /** 读取已缓存的分析结果 */
  @GetMapping("/{id}")
  public ProsperityPickResultDTO get(@PathVariable Long id) {
    return service.get(id);
  }

  /** 获取报告详情 HTML（懒生成） */
  @GetMapping("/{id}/report")
  public Map<String, String> report(@PathVariable Long id) {
    String html = service.getReportHtml(id);
    return Map.of("html", html);
  }

  /** 异步生成信息图（懒生成） */
  @PostMapping("/{id}/infographic")
  public Map<String, String> infographic(@PathVariable Long id) {
    String url = service.generateInfographic(id);
    return Map.of("imageUrl", url);
  }

  /** 最近 10 条分析记录 */
  @GetMapping("/recent")
  public List<ProsperityPickRecentDTO> recent() {
    return service.recent();
  }
}
