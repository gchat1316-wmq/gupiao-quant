package com.quant.service.invest;

import java.util.List;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quant.dto.invest.PoolFieldUpdateRequest;
import com.quant.dto.invest.PoolItemDTO;
import com.quant.dto.invest.PoolSaveRequest;
import com.quant.dto.invest.SopCheckupDTO;

import lombok.RequiredArgsConstructor;

/**
 * 投资池（龙江/谢博）门面：对外暴露稳定的公共 API，缓存 / 事务边界统一在此声明，实际业务委托给聚焦的协作服务。
 *
 * <ul>
 *   <li>{@link InvestPoolService} — 股票池增删改查、内联编辑、拖拽排序、DTO 富化
 *   <li>{@link InvestSopService} — 实战选股 SOP 三大数字体检
 *   <li>{@link InvestValuationService} — 10×PS 估值三档（含 {@code ValuationVerdict}）
 *   <li>{@link InvestMathUtils} — 同比 / 景气度 / 季度 / 数值解析等纯计算
 * </ul>
 *
 * <p>{@code @Cacheable}/{@code @CacheEvict} 的 key 表达式仍按调用方参数计算，因此保留在门面公共方法上， 由门面调用无缓存注解的协作方法完成实际工作。
 */
@Service
@RequiredArgsConstructor
public class InvestService {

  private final InvestPoolService poolService;
  private final InvestSopService sopService;

  // ===== 股票池管理 =====

  /**
   * 列出股票池条目。poolType 为 null/blank 表示全部；否则按 poolType 在 DB 层过滤， 只为该 poolType 的代码拉实时行情 / 年初收盘价，避免一次切换
   * tab 时把所有池子的 行情和 K 线都拉一遍（外部 HTTP 调用是主要瓶颈）。
   *
   * <p>30s 缓存：行情 30s 内基本不变，反复切换 tab / 刷新页面直接走 cache。
   */
  @Cacheable(value = "stockPool", key = "#poolType == null ? 'all' : #poolType")
  @Transactional(readOnly = true)
  public List<PoolItemDTO> listPool(String poolType) {
    return poolService.listPool(poolType);
  }

  /** 兼容入口：列出全部股票池。 */
  public List<PoolItemDTO> listPool() {
    return listPool(null);
  }

  /** poolType → 中文标签。tech_ai 在 DB 中仍是 "tech_ai"，显示为"科技AI"。 */
  public static String poolTypeLabelOf(String poolType) {
    return InvestPoolService.poolTypeLabelOf(poolType);
  }

  @CacheEvict(value = "stockPool", allEntries = true)
  @Transactional
  public PoolItemDTO addToPool(PoolSaveRequest req) {
    return poolService.addToPool(req);
  }

  @CacheEvict(value = "stockPool", allEntries = true)
  @Transactional
  public PoolItemDTO updatePool(Integer id, PoolSaveRequest req) {
    return poolService.updatePool(id, req);
  }

  /** 单字段更新（内联编辑）。空字符串视为清空（设为 null），允许撤销字段值。 */
  @CacheEvict(value = "stockPool", allEntries = true)
  @Transactional
  public PoolItemDTO updateField(Integer id, PoolFieldUpdateRequest req) {
    return poolService.updateField(id, req);
  }

  @CacheEvict(value = "stockPool", allEntries = true)
  @Transactional
  public void removeFromPool(Integer id) {
    poolService.removeFromPool(id);
  }

  /**
   * 批量更新股票池条目的 displayOrder（拖拽排序）。 入参每项至少要有 id 与 displayOrder；id 必须存在，displayOrder 必须 ≥ 0。 事务内串行执行
   * N 条 UPDATE，N 通常 ≤ 50，耗时可忽略。
   */
  @CacheEvict(value = "stockPool", allEntries = true)
  @Transactional
  public int reorder(List<InvestPoolService.ReorderItem> items) {
    return poolService.reorder(items);
  }

  // ===== 实战选股 SOP · 三大数字体检 =====

  @Cacheable(value = "sopCheckup", key = "#keyword")
  @Transactional(readOnly = true)
  public SopCheckupDTO sopCheckup(String keyword) {
    return sopService.sopCheckup(keyword);
  }
}
