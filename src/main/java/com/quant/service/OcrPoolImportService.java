package com.quant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.dto.invest.BatchImportRequest;
import com.quant.dto.invest.BatchImportResultDTO;
import com.quant.dto.invest.OcrImportRequest;
import com.quant.dto.invest.OcrParseResultDTO;
import com.quant.dto.invest.OcrParsedItemDTO;
import com.quant.dto.invest.PoolSaveRequest;
import com.quant.entity.InvestStockPool;
import com.quant.entity.TradeStockBasic;
import com.quant.repository.InvestStockPoolRepository;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.service.ai.MiniMaxClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class OcrPoolImportService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SYSTEM_PROMPT =
            "你是一个金融数据识别助手。用户会上传一张包含股票表格的截图（如10倍PS估值表/研报清单），" +
            "请仔细识别图中所有股票，并以严格的 JSON 格式返回。绝不要返回任何解释文字。";

    private static final String USER_PROMPT =
            "请识别图片中的股票表格，提取每只股票的以下字段，返回纯 JSON（不要 ```json 代码块包裹）：\n" +
            "{\n" +
            "  \"items\": [\n" +
            "    {\n" +
            "      \"stockName\": \"股票名称（必填，用中文，如：贵州茅台）\",\n" +
            "      \"stockCode\": \"股票代码（如：600519，没有就留空字符串）\",\n" +
            "      \"undervaluedPrice\": null,\n" +
            "      \"fairPrice\": null,\n" +
            "      \"overvaluedPrice\": null,\n" +
            "      \"targetBuyPrice\": null,\n" +
            "      \"targetSellPrice\": null,\n" +
            "      \"revenueForecastY0\": 该股票今年（当前年度）预测营收，单位亿元，纯数字（如 50.5），无则 null,\n" +
            "      \"revenueForecastY1\": 明年预测营收（亿），无则 null,\n" +
            "      \"revenueForecastY2\": 后年预测营收（亿），无则 null,\n" +
            "      \"memo\": \"可选备注，可记录截图的额外信息（如：毛利率42%、净利率15%、近5年最低PS 6.33倍、目前市值139亿、年初涨幅231%等）\"\n" +
            "    }\n" +
            "  ]\n" +
            "}\n" +
            "注意：\n" +
            "1. 如果图中字段不明确，对应字段填 null。\n" +
            "2. 价格/营收都是数字，去掉单位（亿/元/%）。\n" +
            "3. memo 可以拼接图中其它有用字段，方便用户查看。\n" +
            "4. 必须返回合法 JSON。";

    private final MiniMaxClient miniMaxClient;
    private final TradeStockBasicRepository stockBasicRepository;
    private final InvestStockPoolRepository poolRepository;
    private final InvestService investService;

    public OcrPoolImportService(MiniMaxClient miniMaxClient,
                                TradeStockBasicRepository stockBasicRepository,
                                InvestStockPoolRepository poolRepository,
                                InvestService investService) {
        this.miniMaxClient = miniMaxClient;
        this.stockBasicRepository = stockBasicRepository;
        this.poolRepository = poolRepository;
        this.investService = investService;
    }

    public OcrParseResultDTO parseImage(OcrImportRequest req) {
        if (req.getImageBase64() == null || req.getImageBase64().isBlank()) {
            throw new IllegalArgumentException("图片不能为空");
        }
        String aiResp;
        try {
            aiResp = miniMaxClient.chatCompleteVision(SYSTEM_PROMPT, USER_PROMPT, req.getImageBase64(), null);
        } catch (Exception e) {
            log.warn("MiniMax 视觉识别失败：{}", e.getMessage(), e);
            throw new IllegalStateException("AI 识别失败：" + e.getMessage());
        }
        log.info("OCR 返回长度: {}", aiResp == null ? 0 : aiResp.length());

        String json = stripJsonFence(aiResp);
        List<OcrParsedItemDTO> parsed = parseJsonToItems(json);

        String defaultPoolType = req.getDefaultPoolType() != null && !req.getDefaultPoolType().isBlank()
                ? req.getDefaultPoolType() : "tech_vc";

        for (OcrParsedItemDTO it : parsed) {
            if (it.getPoolType() == null || it.getPoolType().isBlank()) {
                it.setPoolType(defaultPoolType);
            }
            if (it.getStatus() == null || it.getStatus().isBlank()) {
                it.setStatus("watching");
            }
            tryMatch(it);
        }

        int matched = (int) parsed.stream().filter(OcrParsedItemDTO::isMatched).count();
        return OcrParseResultDTO.builder()
                .totalParsed(parsed.size())
                .matched(matched)
                .items(parsed)
                .rawAiText(aiResp)
                .build();
    }

    /** 把识别项匹配到 trade_stock_basic 找到完整的 stockCode（带后缀）+ 校正 stockName。 */
    private void tryMatch(OcrParsedItemDTO it) {
        String code = it.getStockCode();
        String name = it.getStockName();

        if (code != null && !code.isBlank()) {
            String trimmed = code.trim();
            // 已带后缀
            if (trimmed.contains(".")) {
                Optional<TradeStockBasic> exact = stockBasicRepository.findByStockCode(trimmed);
                if (exact.isPresent()) {
                    fillFromBasic(it, exact.get());
                    return;
                }
            } else if (trimmed.matches("\\d{4,8}")) {
                List<TradeStockBasic> byPrefix = stockBasicRepository.findByStockCodePrefix(trimmed);
                if (!byPrefix.isEmpty()) {
                    fillFromBasic(it, byPrefix.get(0));
                    return;
                }
            }
        }
        if (name != null && !name.isBlank()) {
            List<TradeStockBasic> byName = stockBasicRepository.findByStockNameLike(name.trim());
            if (!byName.isEmpty()) {
                fillFromBasic(it, byName.get(0));
                return;
            }
        }
        it.setMatched(false);
    }

    private void fillFromBasic(OcrParsedItemDTO it, TradeStockBasic basic) {
        it.setStockCode(basic.getStockCode());
        if (basic.getStockName() != null && !basic.getStockName().isBlank()) {
            it.setStockName(basic.getStockName());
        }
        it.setMatched(true);
    }

    private String stripJsonFence(String text) {
        if (text == null) return "{}";
        String t = text.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            if (firstNl > 0) t = t.substring(firstNl + 1);
            int endFence = t.lastIndexOf("```");
            if (endFence >= 0) t = t.substring(0, endFence);
            t = t.trim();
        }
        // 提取首个 { 到末尾 }
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start >= 0 && end > start) return t.substring(start, end + 1);
        return t;
    }

    private List<OcrParsedItemDTO> parseJsonToItems(String json) {
        List<OcrParsedItemDTO> result = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode arr = root.path("items");
            if (!arr.isArray()) return result;
            for (JsonNode n : arr) {
                OcrParsedItemDTO it = OcrParsedItemDTO.builder()
                        .stockName(textOrNull(n, "stockName"))
                        .stockCode(textOrNull(n, "stockCode"))
                        .undervaluedPrice(decimalOrNull(n, "undervaluedPrice"))
                        .fairPrice(decimalOrNull(n, "fairPrice"))
                        .overvaluedPrice(decimalOrNull(n, "overvaluedPrice"))
                        .targetBuyPrice(decimalOrNull(n, "targetBuyPrice"))
                        .targetSellPrice(decimalOrNull(n, "targetSellPrice"))
                        .revenueForecastY0(decimalOrNull(n, "revenueForecastY0"))
                        .revenueForecastY1(decimalOrNull(n, "revenueForecastY1"))
                        .revenueForecastY2(decimalOrNull(n, "revenueForecastY2"))
                        .memo(textOrNull(n, "memo"))
                        .matched(false)
                        .build();
                if (it.getStockName() != null && !it.getStockName().isBlank()) {
                    result.add(it);
                }
            }
        } catch (Exception e) {
            log.error("解析 OCR JSON 失败: {}", e.getMessage());
            throw new IllegalStateException("AI 返回内容无法解析为 JSON：" + e.getMessage());
        }
        return result;
    }

    private String textOrNull(JsonNode n, String key) {
        JsonNode v = n.path(key);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText("");
        return s.isBlank() ? null : s.trim();
    }

    private BigDecimal decimalOrNull(JsonNode n, String key) {
        JsonNode v = n.path(key);
        if (v.isMissingNode() || v.isNull()) return null;
        if (v.isNumber()) return v.decimalValue();
        String s = v.asText("").trim();
        if (s.isEmpty()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Transactional
    public BatchImportResultDTO batchImport(BatchImportRequest req) {
        if (req.getItems() == null || req.getItems().isEmpty()) {
            return BatchImportResultDTO.builder().imported(0).skipped(0).failed(0).failures(List.of()).build();
        }
        int imported = 0, skipped = 0, failed = 0;
        List<String> failures = new ArrayList<>();
        for (OcrParsedItemDTO it : req.getItems()) {
            try {
                String keyword = (it.getStockCode() != null && !it.getStockCode().isBlank())
                        ? it.getStockCode().trim()
                        : (it.getStockName() != null ? it.getStockName().trim() : "");
                if (keyword.isEmpty()) {
                    failed++;
                    failures.add("(空) 无法匹配");
                    continue;
                }
                // 已存在：跳过（保持现有数据不被覆盖）
                Optional<InvestStockPool> exist = poolRepository.findByStockCode(
                        normalizeCode(it.getStockCode()));
                if (exist.isEmpty() && it.getStockCode() != null) {
                    exist = poolRepository.findByStockCode(it.getStockCode().trim());
                }
                if (exist.isPresent()) {
                    skipped++;
                    continue;
                }
                PoolSaveRequest saveReq = new PoolSaveRequest();
                saveReq.setKeyword(keyword);
                saveReq.setPoolType(it.getPoolType() != null ? it.getPoolType() : "tech_vc");
                saveReq.setStatus(it.getStatus() != null ? it.getStatus() : "watching");
                saveReq.setMemo(it.getMemo());
                saveReq.setUndervaluedPrice(it.getUndervaluedPrice());
                saveReq.setFairPrice(it.getFairPrice());
                saveReq.setOvervaluedPrice(it.getOvervaluedPrice());
                saveReq.setTargetBuyPrice(it.getTargetBuyPrice());
                saveReq.setTargetSellPrice(it.getTargetSellPrice());
                saveReq.setRevenueForecastY0(it.getRevenueForecastY0());
                saveReq.setRevenueForecastY1(it.getRevenueForecastY1());
                saveReq.setRevenueForecastY2(it.getRevenueForecastY2());
                investService.addToPool(saveReq);
                imported++;
            } catch (IllegalArgumentException e) {
                if (e.getMessage() != null && e.getMessage().contains("已在股票池")) {
                    skipped++;
                } else {
                    failed++;
                    failures.add((it.getStockName() != null ? it.getStockName() : it.getStockCode()) + ": " + e.getMessage());
                }
            } catch (Exception e) {
                failed++;
                failures.add((it.getStockName() != null ? it.getStockName() : it.getStockCode()) + ": " + e.getMessage());
            }
        }
        return BatchImportResultDTO.builder()
                .imported(imported)
                .skipped(skipped)
                .failed(failed)
                .failures(failures)
                .build();
    }

    private String normalizeCode(String code) {
        if (code == null) return null;
        return code.trim();
    }
}
