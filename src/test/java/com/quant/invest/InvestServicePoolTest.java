package com.quant.invest;

import com.quant.dto.invest.PoolItemDTO;
import com.quant.entity.InvestStockPool;
import com.quant.entity.TradeStockBasic;
import com.quant.repository.InvestStockPoolRepository;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.repository.TradeStockDailyRepository;
import com.quant.repository.TradeStockFinancialRepository;
import com.quant.service.InvestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InvestService - 股票池列表")
class InvestServicePoolTest {

    @Mock TradeStockBasicRepository stockBasicRepo;
    @Mock TradeStockFinancialRepository financialRepo;
    @Mock TradeStockDailyRepository dailyRepo;
    @Mock InvestStockPoolRepository poolRepo;

    InvestService service;

    @BeforeEach
    void setUp() {
        service = new InvestService(stockBasicRepo, financialRepo, dailyRepo, poolRepo);
    }

    @Test
    @DisplayName("基础表缺失时使用股票池自身的公司名称")
    void listPoolUsesPoolStockNameWhenBasicMissing() {
        InvestStockPool pool = new InvestStockPool();
        pool.setId(14);
        pool.setStockCode("688296");
        pool.setStockName("金海通");
        pool.setPoolType("tech_vc");

        when(poolRepo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(pool));
        when(stockBasicRepo.findByStockCodeIn(org.mockito.ArgumentMatchers.argThat(codes ->
                codes.contains("688296")))).thenReturn(Collections.emptyList());
        when(financialRepo.findLatestByStockCodes(List.of("688296"))).thenReturn(Collections.emptyList());
        when(dailyRepo.findLatestByStockCodes(List.of("688296"))).thenReturn(Collections.emptyList());
        when(dailyRepo.findFirstAfterDateByStockCodes(org.mockito.ArgumentMatchers.eq(List.of("688296")),
                org.mockito.ArgumentMatchers.any())).thenReturn(Collections.emptyList());

        List<PoolItemDTO> result = service.listPool();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStockName()).isEqualTo("金海通");
    }

    @Test
    @DisplayName("基础表代码后缀大小写不一致时仍显示公司名称")
    void listPoolMatchesBasicCodeCaseInsensitive() {
        InvestStockPool pool = new InvestStockPool();
        pool.setId(24);
        pool.setStockCode("688525.sh");
        pool.setPoolType("tech_ai");

        TradeStockBasic basic = new TradeStockBasic();
        basic.setStockCode("688525.SH");
        basic.setStockName("佰维存储");

        when(poolRepo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(pool));
        when(stockBasicRepo.findByStockCodeIn(org.mockito.ArgumentMatchers.argThat(codes ->
                codes.contains("688525.sh") && codes.contains("688525.SH")))).thenReturn(List.of(basic));
        when(financialRepo.findLatestByStockCodes(List.of("688525.sh"))).thenReturn(Collections.emptyList());
        when(dailyRepo.findLatestByStockCodes(List.of("688525.sh"))).thenReturn(Collections.emptyList());
        when(dailyRepo.findFirstAfterDateByStockCodes(org.mockito.ArgumentMatchers.eq(List.of("688525.sh")),
                org.mockito.ArgumentMatchers.any())).thenReturn(Collections.emptyList());

        List<PoolItemDTO> result = service.listPool();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStockName()).isEqualTo("佰维存储");
    }
}
