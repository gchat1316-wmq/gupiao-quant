package com.quant.prosperitystrong;

import com.quant.entity.ProsperityPickDaily;
import com.quant.entity.ProsperityStockPool;
import com.quant.repository.ProsperityPickDailyRepository;
import com.quant.repository.ProsperityStockPoolRepository;
import com.quant.service.prosperitystrong.ProsperityPoolService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回归测试: 龙头候选"入池"动作必须写到 prosperity_stock_pool (热点股票池),
 *          不能再写到 invest_stock_pool (龙江投资股票池)。
 *
 * <p>背景: 原 {@code ProsperityStrongController.promote} 把入池数据塞进
 * {@code invest_stock_pool} 并硬编码 pool_type='tech_vc', 业务语义不对:
 * 1) 龙江投资股票池 enum 没有 hot 选项;
 * 2) 龙江=中长期持仓 / 热点=短线波段,两个池子业务模型不同。
 *
 * <p>本测试用隔离的 stock_code (TEST.PS.POOL.*) 和远期 snap_date 避免污染生产数据,
 * 用 ProsperityPoolService 真实写入 MySQL, 任何 schema 缺失 / 字段错位会立即浮上来。
 */
@SpringBootTest
@ActiveProfiles("test")
class ProsperityPoolServiceTest {

    /** 测试用股票代码 — 用特殊前缀确保不和真实数据冲突 */
    private static final String CODE = "TEST.PS.POOL.001";
    private static final LocalDate SNAP_D1 = LocalDate.of(2099, 1, 1);
    private static final LocalDate SNAP_D2 = LocalDate.of(2099, 1, 2);

    @Autowired private ProsperityPoolService poolService;
    @Autowired private ProsperityPickDailyRepository pickRepo;
    @Autowired private ProsperityStockPoolRepository poolRepo;
    @Autowired private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
        seedPick(SNAP_D1, new BigDecimal("85"), new BigDecimal("20.00"));
        seedPick(SNAP_D2, new BigDecimal("88"), new BigDecimal("22.00"));
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            poolRepo.deleteByStockCode(CODE);
            // 同时清掉 pick 记录
            pickRepo.findBySnapDateAndStockCode(SNAP_D1, CODE).ifPresent(pickRepo::delete);
            pickRepo.findBySnapDateAndStockCode(SNAP_D2, CODE).ifPresent(pickRepo::delete);
        });
    }

    private void seedPick(LocalDate d, BigDecimal combined, BigDecimal price) {
        ProsperityPickDaily p = new ProsperityPickDaily();
        p.setSnapDate(d);
        p.setStockCode(CODE);
        p.setStockName("测试龙头候选");
        p.setSectorName("测试板块");
        p.setCombinedScore(combined);
        p.setFinanceScore(new BigDecimal("90"));
        p.setMainlineScore(new BigDecimal("80"));
        p.setNetMarginAvg4q(new BigDecimal("15.5"));
        p.setLatestPrice(price);
        p.setBuyLeftPrice(new BigDecimal("19.00"));
        p.setBuyRightPrice(new BigDecimal("21.00"));
        p.setSellTarget1(new BigDecimal("28.00"));
        p.setSellTarget2(new BigDecimal("32.00"));
        p.setStopLossPrice(new BigDecimal("17.50"));
        p.setCorePositionPct(new BigDecimal("8.00"));
        p.setTacticalPositionPct(new BigDecimal("3.00"));
        p.setActionSignal("add");
        pickRepo.save(p);
    }

    @Test
    void promote_firstTime_createsNewRowWithPoolCount1() {
        Map<String, Object> r = poolService.promote(CODE, SNAP_D1);

        assertEquals("已加入热点股票池", r.get("message"));
        assertEquals(CODE, r.get("stockCode"));
        assertEquals(SNAP_D1.toString(), r.get("snapDate"));
        assertEquals(true, r.get("isNew"));
        assertEquals(1, r.get("poolCount"));

        // 真库验证
        Optional<ProsperityStockPool> saved = poolRepo.findByStockCode(CODE);
        assertTrue(saved.isPresent(), "prosperity_stock_pool 必须有新行");
        ProsperityStockPool p = saved.get();
        assertEquals("测试龙头候选", p.getStockName());
        assertEquals("测试板块", p.getSectorName());
        assertEquals(1, p.getPoolCount());
        assertEquals(SNAP_D1, p.getLastSnapDate());
        assertEquals("watching", p.getStatus());
        assertNotNull(p.getLastAddedAt(), "last_added_at 必须被填充");
        assertNotNull(p.getMemo(), "memo 必须被填充");
        assertTrue(p.getMemo().contains(SNAP_D1.toString()),
                "memo 应包含入池日期, 实际: " + p.getMemo());
        assertTrue(p.getMemo().contains("add"), "memo 应包含 action_signal");
    }

    @Test
    void promote_secondTime_appendsMemoAndIncrementsPoolCount() {
        // 第一次入池
        poolService.promote(CODE, SNAP_D1);
        // 第二次入池(更新快照日期, 价格更高)
        Map<String, Object> r = poolService.promote(CODE, SNAP_D2);

        assertEquals("已更新热点股票池条目", r.get("message"));
        assertEquals(false, r.get("isNew"));
        assertEquals(2, r.get("poolCount"));

        Optional<ProsperityStockPool> saved = poolRepo.findByStockCode(CODE);
        assertTrue(saved.isPresent());
        ProsperityStockPool p = saved.get();
        assertEquals(2, p.getPoolCount());
        assertEquals(SNAP_D2, p.getLastSnapDate(), "last_snap_date 应更新到最新入池日期");
        assertEquals(new BigDecimal("22.00"), p.getLatestPrice(),
                "latest_price 应更新到最新入池的快照价格");
        // memo 应包含两次入池的日期
        assertTrue(p.getMemo().contains(SNAP_D1.toString()),
                "memo 应包含首次入池日期, 实际: " + p.getMemo());
        assertTrue(p.getMemo().contains(SNAP_D2.toString()),
                "memo 应包含最新入池日期, 实际: " + p.getMemo());
        // 两次入池时间应不同(有先后)
        assertNotNull(p.getFirstAddedAt(), "first_added_at 应有值");
        assertNotNull(p.getLastAddedAt(), "last_added_at 应有值");
        assertTrue(!p.getFirstAddedAt().equals(p.getLastAddedAt())
                        || p.getFirstAddedAt().isBefore(p.getLastAddedAt())
                        || p.getFirstAddedAt().isEqual(p.getLastAddedAt()),
                "first/last_added_at 应有先后或相同(同毫秒内连续两次入池)");
    }

    @Test
    void promote_pickNotFound_throwsIllegalArgument() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> poolService.promote("TEST.PS.POOL.NOT.EXIST", SNAP_D1));
        assertTrue(ex.getMessage().contains("未找到候选"),
                "错误信息应提示未找到候选, 实际: " + ex.getMessage());
    }

    @Test
    void list_returnsAllPoolItemsByLastAddedDesc() {
        poolService.promote(CODE, SNAP_D1);
        // 再入一个不同股票
        String otherCode = "TEST.PS.POOL.002";
        seedPickForCode(SNAP_D1, otherCode);
        poolService.promote(otherCode, SNAP_D1);

        List<ProsperityStockPool> list = poolService.list();
        assertTrue(list.size() >= 2, "列表应至少包含刚入池的 2 条");
        // 池子里必须能查到我们测试用的 code (其它真实数据可能也在这张表里)
        assertTrue(list.stream().anyMatch(p -> CODE.equals(p.getStockCode())));
        assertTrue(list.stream().anyMatch(p -> otherCode.equals(p.getStockCode())));

        // 清理
        tx.executeWithoutResult(s -> {
            poolRepo.deleteByStockCode(otherCode);
            pickRepo.findBySnapDateAndStockCode(SNAP_D1, otherCode).ifPresent(pickRepo::delete);
        });
    }

    private void seedPickForCode(LocalDate d, String code) {
        ProsperityPickDaily p = new ProsperityPickDaily();
        p.setSnapDate(d);
        p.setStockCode(code);
        p.setStockName("测试龙头候选2");
        p.setSectorName("测试板块2");
        p.setCombinedScore(new BigDecimal("80"));
        p.setLatestPrice(new BigDecimal("15.00"));
        p.setBuyLeftPrice(new BigDecimal("14.00"));
        p.setSellTarget1(new BigDecimal("20.00"));
        p.setStopLossPrice(new BigDecimal("12.50"));
        p.setActionSignal("hold");
        pickRepo.save(p);
    }
}
