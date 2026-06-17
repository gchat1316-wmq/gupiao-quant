package com.quant.service.lynchinvest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.dto.lynchinvest.LynchAnalysisDetailDTO;
import com.quant.dto.lynchinvest.LynchAnalysisListItemDTO;
import com.quant.dto.lynchinvest.LynchQuoteDTO;
import com.quant.entity.InvestLynchAnalysisRecord;
import com.quant.entity.TradeStockBasic;
import com.quant.repository.InvestLynchAnalysisRecordRepository;
import com.quant.service.StockQueryService;
import com.quant.service.ai.MiniMaxClient;
import com.quant.service.ai.SenseNovaClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LynchInvestAnalysisService {

    private final InvestLynchAnalysisRecordRepository repository;
    private final StockQueryService stockQueryService;
    private final LynchInvestService lynchInvestService;
    private final MiniMaxClient miniMaxClient;
    private final SenseNovaClient senseNovaClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public LynchAnalysisDetailDTO create(String keyword) {
        TradeStockBasic basic = stockQueryService.resolveStock(keyword)
                .orElseThrow(() -> new IllegalArgumentException("未找到股票: " + keyword));
        LynchQuoteDTO quote = lynchInvestService.getQuote(keyword);

        InvestLynchAnalysisRecord record = new InvestLynchAnalysisRecord();
        record.setStockCode(basic.getStockCode());
        record.setStockName(basic.getStockName());
        record.setAnalysisDate(LocalDate.now());
        record.setStatus("completed");
        record.setPegValue(quote.getPeg());
        record.setPegRating(quote.getPegRating());
        record.setConclusion(buildConclusion(quote));
        record.setReportMarkdown(buildReportMarkdown(quote));
        record.setRawSnapshotJson(toSnapshotJson(quote));
        record = repository.save(record);
        return toDetail(record);
    }

    public List<LynchAnalysisListItemDTO> list() {
        return repository.findAllByOrderByIdDesc().stream().map(this::toListItem).toList();
    }

    public LynchAnalysisDetailDTO detail(Long id) {
        return repository.findById(id).map(this::toDetail)
                .orElseThrow(() -> new IllegalArgumentException("未找到分析记录: " + id));
    }

    private String buildConclusion(LynchQuoteDTO quote) {
        return "PEG " + safe(quote.getPeg()) + "，评级" + safeText(quote.getPegRating()) + "。";
    }

    private String buildReportMarkdown(LynchQuoteDTO quote) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(quote.getStockName()).append(" PEG 估值分析\n\n");
        sb.append("## 基本面快照\n\n");
        sb.append("- 股票：").append(quote.getStockName()).append("（").append(quote.getStockCode()).append("）\n");
        sb.append("- 行业：").append(safeText(quote.getSectorName())).append("\n");
        sb.append("- 当前价格：").append(safe(quote.getPrice())).append("\n");
        sb.append("- PE(TTM)：").append(safe(quote.getPeTtm())).append("\n");
        sb.append("- PB：").append(safe(quote.getPb())).append("\n");
        sb.append("- 总市值(亿)：").append(safe(quote.getMarketCap())).append("\n\n");
        sb.append("## PEG 核心分析\n\n");
        sb.append("- CAGR：").append(safe(quote.getCagrPct())).append("%\n");
        sb.append("- PEG：").append(safe(quote.getPeg())).append("\n");
        sb.append("- 评级：").append(safeText(quote.getPegRating())).append("\n\n");
        sb.append("## 综合结论\n\n");
        sb.append(buildConclusion(quote)).append("\n\n");
        sb.append("> 本报告为系统自动生成，仅供学习研究，不构成投资建议。\n");
        return sb.toString();
    }

    private String toSnapshotJson(LynchQuoteDTO quote) {
        try {
            return objectMapper.writeValueAsString(quote);
        } catch (Exception e) {
            return "{}";
        }
    }

    private LynchAnalysisListItemDTO toListItem(InvestLynchAnalysisRecord record) {
        return LynchAnalysisListItemDTO.builder()
                .id(record.getId())
                .stockCode(record.getStockCode())
                .stockName(record.getStockName())
                .analysisDate(record.getAnalysisDate())
                .status(record.getStatus())
                .pegValue(record.getPegValue())
                .pegRating(record.getPegRating())
                .conclusion(record.getConclusion())
                .createdAt(record.getCreatedAt())
                .build();
    }

    private LynchAnalysisDetailDTO toDetail(InvestLynchAnalysisRecord record) {
        return LynchAnalysisDetailDTO.builder()
                .id(record.getId())
                .stockCode(record.getStockCode())
                .stockName(record.getStockName())
                .analysisDate(record.getAnalysisDate())
                .status(record.getStatus())
                .pegValue(record.getPegValue())
                .pegRating(record.getPegRating())
                .conclusion(record.getConclusion())
                .reportMarkdown(record.getReportMarkdown())
                .rawSnapshotJson(record.getRawSnapshotJson())
                .errorMessage(record.getErrorMessage())
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .build();
    }

    private String safe(Object value) {
        return value == null ? "—" : String.valueOf(value);
    }

    private String safeText(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
