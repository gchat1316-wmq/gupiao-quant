package com.quant.controller;

import java.util.List;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.quant.dto.position.LegendDTO;
import com.quant.dto.position.PositionAdviceDTO;
import com.quant.dto.position.PositionAdviceRequest;
import com.quant.service.position.PositionManagementService;

import lombok.RequiredArgsConstructor;

/**
 * 仓位管理系统 REST API。
 *
 * <p>POST /api/position-management/advise 计算单笔仓位建议 GET /api/position-management/legends 传奇交易员案例数据
 * GET /api/position-management/presets Minervini 风报档位预设
 *
 * <p>无数据库依赖,纯计算。
 */
@RestController
@RequestMapping("/api/position-management")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class PositionManagementController {

  private final PositionManagementService service;

  @PostMapping("/advise")
  public PositionAdviceDTO advise(@RequestBody PositionAdviceRequest request) {
    return service.advise(request);
  }

  @GetMapping("/legends")
  public List<LegendDTO> legends() {
    return service.legends();
  }

  @GetMapping("/presets")
  public List<LegendDTO.RiskRewardPreset> presets() {
    return service.presets();
  }
}
