package com.quant.service.xieboinvest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quant.dto.xieboinvest.XieboWeeklyOpportunitySlotDTO;
import com.quant.dto.xieboinvest.XieboWeeklyOpportunityUpdateRequest;
import com.quant.entity.InvestXieboWatchlist;
import com.quant.entity.XieboWeeklyOpportunitySlot;
import com.quant.repository.InvestXieboWatchlistRepository;
import com.quant.repository.XieboWeeklyOpportunitySlotRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 谢博投资 · 每周重点股票服务。
 *
 * <p>不变量： 1. 每个 poolType 固定返回 9 个 slot（不足补空） 2. slot 顺序按 slotIndex 升序 3. update()
 * 是「全量替换」语义（事务内先删后插） 4. stockName 实时联动 invest_xiebo_watchlist 补全
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class XieboWeeklyOpportunityService {

  /** 谢博投资自己的 3 个分类 */
  public static final List<String> ALLOWED_POOL_TYPES = List.of("watch", "focus", "explore");

  public static final int SLOTS_PER_POOL = 9;

  private final XieboWeeklyOpportunitySlotRepository slotRepo;
  private final InvestXieboWatchlistRepository watchlistRepo;

  // ── 读 ──

  @Cacheable(value = "xieboWeeklyOpportunity", key = "#poolType")
  @Transactional(readOnly = true)
  public List<XieboWeeklyOpportunitySlotDTO> get(String poolType) {
    validatePoolType(poolType);
    Map<String, String> nameMap = loadWatchlistNameMap();
    return buildSlots(poolType, slotRepo.findByPoolTypeOrderBySlotIndexAsc(poolType), nameMap);
  }

  @Cacheable(value = "xieboWeeklyOpportunity", key = "'all'")
  @Transactional(readOnly = true)
  public List<XieboWeeklyOpportunitySlotDTO> listAll() {
    List<XieboWeeklyOpportunitySlotDTO> all = new ArrayList<>();
    for (String type : ALLOWED_POOL_TYPES) {
      all.addAll(get(type));
    }
    return all;
  }

  // ── 写 ──

  @CacheEvict(value = "xieboWeeklyOpportunity", allEntries = true)
  @Transactional
  public List<XieboWeeklyOpportunitySlotDTO> update(
      String poolType, XieboWeeklyOpportunityUpdateRequest req) {
    validatePoolType(poolType);
    validateSlots(req);

    slotRepo.deleteByPoolType(poolType);
    slotRepo.flush();

    List<XieboWeeklyOpportunitySlot> rows = new ArrayList<>();
    for (XieboWeeklyOpportunityUpdateRequest.SlotItem item : req.getSlots()) {
      XieboWeeklyOpportunitySlot s = new XieboWeeklyOpportunitySlot();
      s.setPoolType(poolType);
      s.setSlotIndex(item.getSlotIndex());
      s.setStockCode(blankToNull(item.getStockCode()));
      s.setReason(blankToNull(item.getReason()));
      rows.add(s);
    }
    slotRepo.saveAll(rows);
    slotRepo.flush();

    log.info("Xiebo weekly opportunity updated: poolType={}, slotCount={}", poolType, rows.size());

    Map<String, String> nameMap = loadWatchlistNameMap();
    return buildSlots(poolType, rows, nameMap);
  }

  // ── 内部 ──

  private Map<String, String> loadWatchlistNameMap() {
    Map<String, String> map = new HashMap<>();
    for (InvestXieboWatchlist w : watchlistRepo.findAllByOrderByDisplayOrderAscCreatedAtAsc()) {
      if (w.getStockCode() != null) {
        map.put(w.getStockCode(), w.getStockName());
      }
    }
    return map;
  }

  private List<XieboWeeklyOpportunitySlotDTO> buildSlots(
      String poolType, List<XieboWeeklyOpportunitySlot> rows, Map<String, String> nameMap) {
    Map<Integer, XieboWeeklyOpportunitySlot> byIndex = new HashMap<>();
    for (XieboWeeklyOpportunitySlot s : rows) {
      byIndex.put(s.getSlotIndex(), s);
    }

    List<XieboWeeklyOpportunitySlotDTO> out = new ArrayList<>(SLOTS_PER_POOL);
    for (int i = 0; i < SLOTS_PER_POOL; i++) {
      XieboWeeklyOpportunitySlot s = byIndex.get(i);
      if (s == null || s.getStockCode() == null) {
        out.add(XieboWeeklyOpportunitySlotDTO.builder().poolType(poolType).slotIndex(i).build());
      } else {
        out.add(
            XieboWeeklyOpportunitySlotDTO.builder()
                .poolType(poolType)
                .slotIndex(i)
                .stockCode(s.getStockCode())
                .stockName(nameMap.get(s.getStockCode()))
                .reason(s.getReason())
                .updatedAt(s.getUpdatedAt())
                .build());
      }
    }
    return out;
  }

  private void validatePoolType(String poolType) {
    if (poolType == null || !ALLOWED_POOL_TYPES.contains(poolType)) {
      throw new IllegalArgumentException(
          "不支持的 poolType：" + poolType + "（允许：" + ALLOWED_POOL_TYPES + "）");
    }
  }

  private void validateSlots(XieboWeeklyOpportunityUpdateRequest req) {
    if (req == null || req.getSlots() == null) {
      throw new IllegalArgumentException("slots 不能为空");
    }
    if (req.getSlots().size() != SLOTS_PER_POOL) {
      throw new IllegalArgumentException(
          "slots 数量必须等于 " + SLOTS_PER_POOL + "，实际 " + req.getSlots().size());
    }
    Set<Integer> seen = new HashSet<>();
    for (XieboWeeklyOpportunityUpdateRequest.SlotItem item : req.getSlots()) {
      if (item.getSlotIndex() == null
          || item.getSlotIndex() < 0
          || item.getSlotIndex() >= SLOTS_PER_POOL) {
        throw new IllegalArgumentException(
            "slotIndex 越界（必须在 0~" + (SLOTS_PER_POOL - 1) + "），实际 " + item.getSlotIndex());
      }
      if (!seen.add(item.getSlotIndex())) {
        throw new IllegalArgumentException("slotIndex 重复：" + item.getSlotIndex());
      }
    }
  }

  private static String blankToNull(String s) {
    return (s == null || s.isBlank()) ? null : s.trim();
  }
}
