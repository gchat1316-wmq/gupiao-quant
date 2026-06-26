package com.quant.prosperitystrong;

import com.quant.dto.prosperitystrong.PickDailyDTO;
import com.quant.entity.ProsperityPickDaily;
import com.quant.entity.TradeStockFinancial;
import com.quant.repository.ProsperityHotSectorRepository;
import com.quant.repository.ProsperityLeaderCandidateRepository;
import com.quant.repository.ProsperityPickDailyRepository;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.repository.TradeStockFinancialRepository;
import com.quant.service.AStockDataQuoteService;
import com.quant.service.prosperitystrong.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * 回归测试: "龙头候选"表格的"近4季净利率"列改成"近3季度营收增速"。
 * DTO 必须把 entity.revenueYoyMin4q(实际存的是 revenueYoyMin3q 数据) 暴露出来。
 */
@SpringBootTest
@ActiveProfiles("test")
class ProsperityPickRevenueGrowthTest {

    @Autowired private ProsperityStrongPipelineService pipeline;
    @Autowired private ProsperityStrongCleanupService cleanup;
    @Autowired private ProsperityPickDailyRepository pickRepo;

    @MockBean private TradeStockBasicRepository basicRepo;
    @MockBean private AStockDataQuoteService aStockDataQuoteService;
    @MockBean private TradeStockFinancialRepository financialRepo;

    @BeforeEach
    void clean() {
        cleanup.clearSnapDate(LocalDate.of(2026, 6, 27));
    }

    @Test
    void pickDailyDTO_exposes_revenueYoyMin3q() throws Exception {
        // 直接构造 entity, 设 revenueYoyMin3q
        ProsperityPickDaily e = new ProsperityPickDaily();
        e.setSnapDate(LocalDate.of(2026, 6, 27));
        e.setStockCode("000657.SZ");
        e.setStockName("中钨高新");
        e.setSectorName("AI算力");
        e.setFinanceScore(new BigDecimal("100"));
        e.setMainlineScore(new BigDecimal("80"));
        e.setCombinedScore(new BigDecimal("85"));
        e.setRevenueYoyMin3q(new BigDecimal("12.34"));
        e.setNetMarginAvg4q(new BigDecimal("8.5"));      // 旧字段, 不再展示
        pickRepo.save(e);

        // 调用 toPickDTO
        Method m = ProsperityStrongPipelineService.class.getDeclaredMethod(
                "toPickDTO", ProsperityPickDaily.class, boolean.class);
        m.setAccessible(true);
        Object dtoObj = m.invoke(pipeline, e, false);
        PickDailyDTO dto = (PickDailyDTO) dtoObj;

        // DTO 必须暴露 revenueYoyMin3q 字段
        BigDecimal rev = readRevenue(dto);
        assertNotNull(rev, "DTO 必须暴露 revenueYoyMin3q 字段, 当前为 null");
        assertEquals(0, new BigDecimal("12.34").compareTo(rev),
                "revenueYoyMin3q 应映射 entity.revenueYoyMin3q");
    }

    /**
     * 用反射读 DTO 字段 — 因为 Lombok @Builder 生成的 getter 名称可能因字段名而变。
     * 优先 revenueYoyMin3q, 退而求其次 revenueYoyMin4q。
     */
    private BigDecimal readRevenue(PickDailyDTO dto) {
        for (String name : new String[]{"getRevenueYoyMin3q", "getRevenueYoyMin4q"}) {
            try {
                Method g = PickDailyDTO.class.getMethod(name);
                return (BigDecimal) g.invoke(dto);
            } catch (NoSuchMethodException ignored) {}
            catch (Exception e) { fail("反射调用 " + name + " 失败: " + e.getMessage()); }
        }
        return null;
    }
}