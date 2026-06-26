package com.quant.service.stockanalysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.dto.stockanalysis.WindResearchContext;
import com.quant.dto.stockanalysis.WindResearchContext.Consensus;
import com.quant.dto.stockanalysis.WindResearchContext.ResearchExcerpt;
import com.quant.service.prosperitystrong.WindAifinMarketClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wind 研报 + 一致预期统一入口。
 *
 * 数据源：
 *   - analytics_data.get_financial_data  →  一致预期 (target price / EPS / rating)
 *   - financial_docs.get_financial_news  →  研报片段 (title + content + date)
 *
 * 缓存策略：
 *   - 24h 单独缓存（区别于 record 的 1h 缓存），key = method:stockCode:stockName
 *   - 即使 record 缓存失效（用户重新分析）也复用 Wind 结果，省 Wind 配额
 *   - 失败不缓存（让下次再试）
 *
 * 失败降级：
 *   - Wind 未安装 / 无 Key → available=false，prompt 注入 "Wind 研报：未启用"
 *   - 调 Wind 报错 → available=false，log warn 但不抛（不阻塞主报告）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WindResearchService {

    /** 24h 缓存 TTL（毫秒） */
    private static final long CACHE_TTL_MS = Duration.ofHours(24).toMillis();

    private final WindAifinMarketClient windClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 24h 缓存：key=method:code:name, value=CachedEntry */
    private final ConcurrentHashMap<String, CachedEntry> cache = new ConcurrentHashMap<>();

    /**
     * 拉取 Wind 研报 + 一致预期。
     * 任何异常都会被吞掉，返回 available=false 的空 context——主报告不受影响。
     *
     * @param stockCode 6 位代码（不含后缀）
     * @param stockName 中文简称（用于 financial_docs query，不能含空格）
     * @param method full / purple_perilla / gaojingqi / five_dimension
     */
    public WindResearchContext fetch(String stockCode, String stockName, String method) {
        long start = System.currentTimeMillis();
        WindResearchContext.WindResearchContextBuilder b = WindResearchContext.builder()
                .method(method)
                .windInstalled(windClient.isInstalled())
                .windHasKey(windClient.hasApiKey())
                .cachedAt(System.currentTimeMillis());

        if (!windClient.isInstalled() || !windClient.hasApiKey()) {
            log.debug("Wind 未安装或无 API Key, 跳过研报拉取: code={} method={}", stockCode, method);
            return b.available(false).build();
        }

        String cacheKey = method + ":" + safe(stockCode) + ":" + safe(stockName);
        CachedEntry cached = cache.get(cacheKey);
        if (cached != null && (System.currentTimeMillis() - cached.cachedAt) < CACHE_TTL_MS) {
            log.info("Wind 研报缓存命中: key={}", cacheKey);
            return cached.context;
        }

        try {
            Consensus consensus = fetchConsensus(stockCode, stockName);
            List<ResearchExcerpt> reports = fetchResearchExcerpts(stockCode, stockName);

            boolean available = (consensus != null && consensus.getSourceRowCount() > 0)
                    || (reports != null && !reports.isEmpty());

            WindResearchContext ctx = b
                    .available(available)
                    .consensus(consensus)
                    .reports(reports)
                    .elapsedMs(System.currentTimeMillis() - start)
                    .build();

            if (available) {
                cache.put(cacheKey, new CachedEntry(ctx, System.currentTimeMillis()));
                log.info("Wind 研报拉取完成并缓存: key={} consensus={} reports={} elapsedMs={}",
                        cacheKey,
                        consensus == null ? "null" : "ok(rows=" + consensus.getSourceRowCount() + ")",
                        reports == null ? 0 : reports.size(),
                        ctx.getElapsedMs());
            } else {
                log.warn("Wind 研报拉取但无可用数据, 不缓存: key={}", cacheKey);
            }
            return ctx;
        } catch (Exception e) {
            log.warn("Wind 研报拉取失败, 降级为空: code={} method={} err={}", stockCode, method, e.getMessage());
            return b.available(false).build();
        }
    }

    // ============================================================
    // 一致预期：analytics_data.get_financial_data
    // ============================================================
    private Consensus fetchConsensus(String stockCode, String stockName) {
        // 优先用真名（"赛腾股份"），fallback 用 code
        String key = safe(stockName);
        if (key.isEmpty()) key = stockCode;
        // query 去空格
        String question = (key + "券商一致预期目标价EPS评级").replaceAll("\\s+", "");

        try {
            JsonNode root = windClient.call("analytics_data", "get_financial_data",
                    Map.of("question", question));
            JsonNode data = unwrapDataArray(root);
            if (data == null || !data.isArray() || data.isEmpty()) {
                log.debug("Wind 一致预期返回空: question={}", question);
                return Consensus.builder().sourceRowCount(0).build();
            }
            JsonNode first = data.get(0);
            JsonNode columns = first.path("columns");
            JsonNode rows = first.path("rows");
            if (rows.size() == 0) {
                return Consensus.builder().sourceRowCount(0).resolvedQuestion(first.path("resolved_question").asText()).build();
            }
            JsonNode row = rows.get(0);
            Consensus.ConsensusBuilder cb = Consensus.builder()
                    .sourceRowCount(rows.size())
                    .resolvedQuestion(first.path("resolved_question").asText(question));

            // 解析列名 → 值（与 Wind 实际列名对齐: Wind代码, 证券简称, 一致预测目标价, 交易币种, 综合评级_中文, 一致预测EPS2026, ...）
            for (int i = 0; i < columns.size() && i < row.size(); i++) {
                String colName = columns.get(i).path("name").asText("");
                JsonNode v = row.get(i);
                switch (colName) {
                    case "综合评级_中文", "综合评级" -> cb.rating(safeText(v));
                    case "一致预测目标价" -> cb.targetPrice(v.isNumber() ? v.doubleValue() : parseDouble(v.asText()));
                    case "交易币种" -> cb.currency(safeText(v));
                    case "一致预测EPS2026", "一致预期EPS2026" -> cb.eps2026(v.isNumber() ? v.doubleValue() : parseDouble(v.asText()));
                    case "一致预测EPS2027", "一致预期EPS2027" -> cb.eps2027(v.isNumber() ? v.doubleValue() : parseDouble(v.asText()));
                    case "一致预测净利润同比2026", "净利润同比2026" -> cb.netProfitGrowth2026(v.isNumber() ? v.doubleValue() : parseDouble(v.asText()));
                    case "一致预测净利润同比2027", "净利润同比2027" -> cb.netProfitGrowth2027(v.isNumber() ? v.doubleValue() : parseDouble(v.asText()));
                    default -> { /* 其他列忽略 */ }
                }
            }
            return cb.build();
        } catch (Exception e) {
            log.warn("Wind 一致预期查询失败: question={} err={}", question, e.getMessage());
            return Consensus.builder().sourceRowCount(0).build();
        }
    }

    // ============================================================
    // 研报片段：financial_docs.get_financial_news 搜"研报/深度报告/投资逻辑"
    // ============================================================
    private List<ResearchExcerpt> fetchResearchExcerpts(String stockCode, String stockName) {
        String key = safe(stockName);
        if (key.isEmpty()) key = stockCode;
        String compact = key.replaceAll("\\s+", "");

        // 3 个互补 query（去空格后无空白字符，符合 financial_docs 约束）
        String[] queries = new String[]{
                compact + "研报",
                compact + "深度报告",
                compact + "投资逻辑"
        };

        List<ResearchExcerpt> out = new ArrayList<>();
        for (String q : queries) {
            if (out.size() >= 5) break;
            try {
                JsonNode root = windClient.call("financial_docs", "get_financial_news",
                        Map.of("query", q, "top_k", 3));
                JsonNode items = unwrapItems(root);
                if (items == null) continue;
                for (JsonNode item : items) {
                    if (out.size() >= 5) break;
                    String title = item.path("title").asText("").trim();
                    if (title.isEmpty()) continue;
                    // 去重：同 title 只保留第一条
                    if (out.stream().anyMatch(r -> title.equals(r.getTitle()))) continue;

                    String content = item.path("content").asText("");
                    if (content.length() > 500) content = content.substring(0, 500) + "...";
                    String date = item.path("date").asText("");
                    String docType = item.path("doc_type").asText("news");
                    double relevance = item.path("relevance").isMissingNode()
                            ? 0.0 : item.path("relevance").asDouble();
                    String source = extractSource(content);
                    out.add(ResearchExcerpt.builder()
                            .title(title)
                            .content(content)
                            .date(date)
                            .docType(docType)
                            .relevance(relevance)
                            .source(source)
                            .build());
                }
            } catch (Exception e) {
                log.warn("Wind 研报片段查询失败: query={} err={}", q, e.getMessage());
            }
        }
        return out;
    }

    /**
     * 把 Wind CLI 的 MCP result 拆出 data 数组。
     * 结构: {content:[{text:"<json string>"}]} → parse 后取 data.data[0]
     */
    private JsonNode unwrapDataArray(JsonNode root) {
        try {
            JsonNode content = root.path("content");
            if (!content.isArray() || content.isEmpty()) return null;
            JsonNode text = content.get(0).path("text");
            if (text.isMissingNode()) return null;
            JsonNode inner = objectMapper.readTree(text.asText());
            return inner.path("data").path("data");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 拆 financial_docs items 数组: {content:[{text:"<json string>"}]} → data.items[]
     */
    private JsonNode unwrapItems(JsonNode root) {
        try {
            JsonNode content = root.path("content");
            if (!content.isArray() || content.isEmpty()) return null;
            JsonNode text = content.get(0).path("text");
            if (text.isMissingNode()) return null;
            JsonNode inner = objectMapper.readTree(text.asText());
            return inner.path("data").path("items");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从研报 content 头部提取"来源: XX"作为发布机构。Wind 实际片段常带 "来源: 经济观察网" 之类前缀。
     */
    private String extractSource(String content) {
        if (content == null) return null;
        int idx = content.indexOf("来源:");
        if (idx < 0) idx = content.indexOf("来源：");
        if (idx < 0) return null;
        String tail = content.substring(idx + 3).trim();
        int nl = tail.indexOf('\n');
        if (nl > 0) tail = tail.substring(0, nl).trim();
        // 只保留短机构名
        if (tail.length() > 30) tail = tail.substring(0, 30);
        return tail.isEmpty() ? null : tail;
    }

    // ============================================================
    // 工具
    // ============================================================
    private String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private String safeText(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        String t = n.asText("").trim();
        return t.isEmpty() ? null : t;
    }

    private Double parseDouble(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** 缓存条目 */
    private record CachedEntry(WindResearchContext context, long cachedAt) {}
}
