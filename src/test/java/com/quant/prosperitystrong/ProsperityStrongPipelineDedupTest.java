package com.quant.prosperitystrong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import com.quant.entity.ProsperityHotSector;
import com.quant.entity.ProsperityLeaderCandidate;
import com.quant.entity.ProsperityPickDaily;
import com.quant.repository.ProsperityHotSectorRepository;
import com.quant.repository.ProsperityLeaderCandidateRepository;
import com.quant.repository.ProsperityPickDailyRepository;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.repository.TradeStockFinancialRepository;
import com.quant.service.AStockDataQuoteService;
import com.quant.service.prosperitystrong.*;

/**
 * 回归测试: 同一只股票出现在多个板块时, picks 列表必须按 stockCode 去重, 否则 prosperity_pick_daily.uk_date_code 唯一键冲突。
 *
 * <p>历史教训: 2026-06-26 流水线报 Duplicate entry '2026-06-26-000657.SZ' for key
 * 'prosperity_pick_daily.uk_date_code'
 */
@SpringBootTest
@ActiveProfiles("test")
class ProsperityStrongPipelineDedupTest {

  @Autowired private ProsperityStrongPipelineService pipeline;
  @Autowired private ProsperityStrongCleanupService cleanup;
  @Autowired private ProsperityPickDailyRepository pickRepo;
  @Autowired private ProsperityLeaderCandidateRepository leaderRepo;
  @Autowired private ProsperityHotSectorRepository sectorRepo;

  @MockBean private HotSectorScanner sectorScanner;
  @MockBean private TradeStockBasicRepository basicRepo;
  @MockBean private AStockDataQuoteService aStockDataQuoteService;
  @MockBean private TradeStockFinancialRepository financialRepo;

  @BeforeEach
  void clean() {
    cleanup.clearSnapDate(LocalDate.of(2026, 6, 26));
  }

  @Test
  void stockAppearingInMultipleSectors_dedupedInPicks() throws Exception {
    // 1) 准备 2 个板块, 1 只股票(000657.SZ)同时出现在两个板块的 leaders 里
    ProsperityHotSector sectorA = new ProsperityHotSector();
    sectorA.setSnapDate(LocalDate.of(2026, 6, 26));
    sectorA.setSectorCode("BK_A");
    sectorA.setSectorName("板块A");
    sectorA.setRankNo(1);
    sectorA.setScore(new BigDecimal("80"));

    ProsperityHotSector sectorB = new ProsperityHotSector();
    sectorB.setSnapDate(LocalDate.of(2026, 6, 26));
    sectorB.setSectorCode("BK_B");
    sectorB.setSectorName("板块B");
    sectorB.setRankNo(2);
    sectorB.setScore(new BigDecimal("70"));

    when(sectorScanner.scan(any(), any())).thenReturn(List.of(sectorA, sectorB));

    // 2) 两个板块各有同一只股票, finance / mainline 全通过
    ProsperityLeaderCandidate leaderA =
        makeLeader(1, "000657.SZ", "中钨高新", new BigDecimal("80"), "板块A", true, true);
    ProsperityLeaderCandidate leaderB =
        makeLeader(2, "000657.SZ", "中钨高新", new BigDecimal("60"), "板块B", true, true);
    when(basicRepo.findByStockCodeIn(anyList())).thenReturn(List.of());
    when(aStockDataQuoteService.fetchQuotes(anyList())).thenReturn(Map.of());

    // 3) 直接走 pipeline 内部构造 picks 逻辑 — 用反射拿 picks 列表
    // 简化: 模拟保存两份 leader, 然后调用 cleanupService + 检查 pick_daily 唯一性
    // 因为完整 pipeline 跑完太重, 这里只验证"重复 stockCode → pick_daily 不重复入库"
    leaderA.setSectorName("板块A");
    leaderB.setSectorName("板块B");
    leaderRepo.saveAll(List.of(leaderA, leaderB));

    // 模拟 pipeline 内部 picks 列表构造: 同股票两条,期望去重保留高 leaderScore 那条
    List<ProsperityPickDaily> picks = new ArrayList<>();
    picks.add(makePick(LocalDate.of(2026, 6, 26), "000657.SZ", "板块A", new BigDecimal("95")));
    picks.add(makePick(LocalDate.of(2026, 6, 26), "000657.SZ", "板块B", new BigDecimal("85")));

    // 调用去重 (走反射拿私有方法 dedupPicks,验证它存在并返回去重后的列表)
    Method dedup = findDedupMethod();
    Object deduped = dedup.invoke(pipeline, picks);
    assertNotNull(deduped, "dedupPicks 方法必须存在并返回非 null");
    @SuppressWarnings("unchecked")
    List<ProsperityPickDaily> result = (List<ProsperityPickDaily>) deduped;
    assertEquals(1, result.size(), "同股票两条 pick 应去重为一条; 当前 size=" + result.size());
    assertEquals("板块A", result.get(0).getSectorName(), "去重应保留 leaderScore/综合分更高的那条");
  }

  private Method findDedupMethod() {
    for (Method m : ProsperityStrongPipelineService.class.getDeclaredMethods()) {
      if (m.getName().equals("dedupPicks") || m.getName().equals("buildPicksDeduped")) {
        m.setAccessible(true);
        return m;
      }
    }
    // 当前还没有这个方法,测试应该失败,提示要加 dedupPicks 方法
    throw new AssertionError("ProsperityStrongPipelineService 缺少 picks 去重方法");
  }

  private ProsperityLeaderCandidate makeLeader(
      int id,
      String code,
      String name,
      BigDecimal leaderScore,
      String sectorName,
      boolean financePassed,
      boolean mainlinePassed) {
    ProsperityLeaderCandidate l = new ProsperityLeaderCandidate();
    l.setId(id);
    l.setSnapDate(LocalDate.of(2026, 6, 26));
    l.setSectorId(1);
    l.setSectorName(sectorName);
    l.setStockCode(code);
    l.setStockName(name);
    l.setLeaderScore(leaderScore);
    l.setFilterPassed(1);
    l.setFinanceScore(new BigDecimal("100"));
    l.setFinancePassed(financePassed ? 1 : 0);
    l.setMainlineScore(new BigDecimal("80"));
    l.setMainlinePassed(mainlinePassed ? 1 : 0);
    return l;
  }

  private ProsperityPickDaily makePick(
      LocalDate date, String code, String sector, BigDecimal combined) {
    ProsperityPickDaily p = new ProsperityPickDaily();
    p.setSnapDate(date);
    p.setStockCode(code);
    p.setStockName("中钨高新");
    p.setSectorName(sector);
    p.setCombinedScore(combined);
    p.setFinanceScore(new BigDecimal("100"));
    p.setMainlineScore(new BigDecimal("80"));
    return p;
  }
}
