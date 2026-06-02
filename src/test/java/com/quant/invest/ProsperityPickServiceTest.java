package com.quant.invest;

import com.quant.config.AiProperties;
import com.quant.dto.invest.ProsperityPickResultDTO;
import com.quant.entity.InvestProsperityPick;
import com.quant.entity.TradeStockBasic;
import com.quant.repository.InvestProsperityPickRepository;
import com.quant.repository.TradeStockDailyRepository;
import com.quant.repository.TradeStockFinancialRepository;
import com.quant.service.ProsperityPickService;
import com.quant.service.StockQueryService;
import com.quant.service.ai.MiniMaxClient;
import com.quant.service.ai.SenseNovaClient;
import com.quant.service.search.WebSearchClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProsperityPickService")
class ProsperityPickServiceTest {

    private static final String REAL_JSON = """
            {
              "summary": {
                "bullets": ["真实分析要点"],
                "oneLiner": "真实分析结论"
              }
            }
            """;

    @Mock StockQueryService stockQueryService;
    @Mock TradeStockFinancialRepository financialRepo;
    @Mock TradeStockDailyRepository dailyRepo;
    @Mock InvestProsperityPickRepository repo;
    @Mock MiniMaxClient miniMaxClient;
    @Mock SenseNovaClient senseNovaClient;
    @Mock WebSearchClient webSearchClient;

    private ProsperityPickService service;

    @BeforeEach
    void setUp() {
        AiProperties aiProperties = new AiProperties();
        aiProperties.setFallbackToMock(true);
        aiProperties.getSensenova().setEnabled(true);
        aiProperties.getSensenova().setApiKey("test-key");
        service = new ProsperityPickService(
                stockQueryService,
                financialRepo,
                dailyRepo,
                repo,
                miniMaxClient,
                senseNovaClient,
                webSearchClient,
                aiProperties
        );
    }

    @Test
    @DisplayName("当天缓存是演示数据时重新分析而不是直接返回缓存")
    void analyzeRefreshesDegradedCache() {
        TradeStockBasic basic = basic();
        InvestProsperityPick cached = entity(1L, 1, "{\"summary\":{\"oneLiner\":\"演示数据\"}}");

        when(stockQueryService.resolveStock("麦格米特")).thenReturn(Optional.of(basic));
        when(repo.findByStockCodeAndAnalysisDate("002851.SZ", LocalDate.now()))
                .thenReturn(Optional.of(cached), Optional.of(cached));
        when(miniMaxClient.chatComplete(anyString(), anyString())).thenReturn(REAL_JSON);
        when(repo.save(any(InvestProsperityPick.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProsperityPickResultDTO result = service.analyze("麦格米特", false);

        assertThat(result.isCached()).isFalse();
        assertThat(result.isDegraded()).isFalse();
        assertThat(result.getAnalysis().path("summary").path("oneLiner").asText()).isEqualTo("真实分析结论");
        verify(miniMaxClient).chatComplete(anyString(), anyString());
    }

    @Test
    @DisplayName("MiniMax 失败时使用 SenseNova 生成真实分析")
    void analyzeUsesSenseNovaWhenMiniMaxFails() {
        TradeStockBasic basic = basic();

        when(stockQueryService.resolveStock("麦格米特")).thenReturn(Optional.of(basic));
        when(repo.findByStockCodeAndAnalysisDate("002851.SZ", LocalDate.now())).thenReturn(Optional.empty());
        when(miniMaxClient.chatComplete(anyString(), anyString())).thenThrow(new IllegalStateException("401 Unauthorized"));
        when(senseNovaClient.chatComplete(anyString(), anyString())).thenReturn(REAL_JSON);
        when(repo.save(any(InvestProsperityPick.class))).thenAnswer(invocation -> {
            InvestProsperityPick e = invocation.getArgument(0);
            e.setId(2L);
            return e;
        });

        ProsperityPickResultDTO result = service.analyze("麦格米特", false);

        assertThat(result.isDegraded()).isFalse();
        assertThat(result.getAnalysis().path("summary").path("oneLiner").asText()).isEqualTo("真实分析结论");
        verify(senseNovaClient).chatComplete(anyString(), anyString());
    }

    private TradeStockBasic basic() {
        TradeStockBasic basic = new TradeStockBasic();
        basic.setStockCode("002851.SZ");
        basic.setStockName("麦格米特");
        basic.setExchange("SZ");
        return basic;
    }

    private InvestProsperityPick entity(Long id, int degraded, String resultJson) {
        InvestProsperityPick entity = new InvestProsperityPick();
        entity.setId(id);
        entity.setStockCode("002851.SZ");
        entity.setStockName("麦格米特");
        entity.setAnalysisDate(LocalDate.now());
        entity.setDegraded(degraded);
        entity.setResultJson(resultJson);
        return entity;
    }
}
