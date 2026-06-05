package com.quant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.quant.config.AiProperties;
import com.quant.dto.invest.ProsperityPickRecentDTO;
import com.quant.dto.invest.ProsperityPickResultDTO;
import com.quant.entity.InvestProsperityPick;
import com.quant.entity.TradeStockBasic;
import com.quant.entity.TradeStockDaily;
import com.quant.entity.TradeStockFinancial;
import com.quant.repository.InvestProsperityPickRepository;
import com.quant.repository.TradeStockDailyRepository;
import com.quant.repository.TradeStockFinancialRepository;
import com.quant.service.ai.MiniMaxClient;
import com.quant.service.ai.SenseNovaClient;
import com.quant.service.search.WebSearchClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProsperityPickService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int FINANCIAL_QUARTERS = 12;
    private static final int RECENT_HISTORY_DAYS = 3;

    private final StockQueryService stockQueryService;
    private final TradeStockFinancialRepository financialRepo;
    private final TradeStockDailyRepository dailyRepo;
    private final InvestProsperityPickRepository repo;
    private final MiniMaxClient miniMaxClient;
    private final SenseNovaClient senseNovaClient;
    private final WebSearchClient webSearchClient;
    private final AiProperties aiProperties;

    public ProsperityPickResultDTO analyze(String keyword, boolean force) {
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("请输入股票名称或代码");
        }
        TradeStockBasic basic = stockQueryService.resolveStock(keyword.trim())
                .orElseThrow(() -> new IllegalArgumentException("未找到股票: " + keyword));

        LocalDate today = LocalDate.now();
        if (!force) {
            Optional<InvestProsperityPick> cached = repo.findByStockCodeAndAnalysisDate(
                    basic.getStockCode(), today);
            if (cached.isPresent()) {
                InvestProsperityPick cachedEntity = cached.get();
                if (cachedEntity.getDegraded() == null || cachedEntity.getDegraded() != 1) {
                    log.info("命中缓存: {} {}", basic.getStockCode(), today);
                    return toResultDTO(cachedEntity, basic, true);
                }
                log.info("命中演示数据缓存，重新分析: {} {}", basic.getStockCode(), today);
            }
        }

        ProsperityPickResultDTO.Profile profile = buildProfile(basic);
        String prompt = buildPrompt(profile, basic);

        String aiJson;
        try {
            aiJson = analyzeWithAi(prompt);
        } catch (Exception e) {
            log.warn("AI 调用失败，不返回演示数据: {}", e.getMessage());
            throw new IllegalStateException("AI 调用失败: " + e.getMessage(), e);
        }

        InvestProsperityPick entity = repo.findByStockCodeAndAnalysisDate(basic.getStockCode(), today)
                .orElseGet(InvestProsperityPick::new);
        entity.setStockCode(basic.getStockCode());
        entity.setStockName(basic.getStockName() != null ? basic.getStockName() : basic.getStockCode());
        entity.setAnalysisDate(today);
        entity.setResultJson(aiJson);
        entity.setDegraded(0);
        if (force) {
            entity.setImageUrl(null);
            entity.setImagePrompt(null);
        }
        InvestProsperityPick saved = repo.save(entity);
        return toResultDTO(saved, basic, false);
    }

    private String analyzeWithAi(String prompt) {
        Exception miniMaxError = null;
        try {
            return normalizeAiJson(miniMaxClient.chatComplete(SYSTEM_PROMPT, prompt));
        } catch (Exception e) {
            miniMaxError = e;
            log.warn("MiniMax 分析失败，尝试 SenseNova: {}", e.getMessage());
        }

        try {
            return normalizeAiJson(senseNovaClient.chatComplete(SYSTEM_PROMPT, prompt));
        } catch (Exception senseNovaError) {
            String message = "MiniMax: " + miniMaxError.getMessage()
                    + "; SenseNova: " + senseNovaError.getMessage();
            throw new IllegalStateException(message, senseNovaError);
        }
    }

    private String normalizeAiJson(String raw) {
        String aiJson = extractJson(raw);
        try {
            MAPPER.readTree(aiJson);
        } catch (Exception e) {
            throw new IllegalStateException("AI 返回不是合法 JSON: " + e.getMessage(), e);
        }
        return aiJson;
    }

    public String generateInfographic(Long id) {
        InvestProsperityPick entity = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("分析记录不存在: " + id));
        if (entity.getImageUrl() != null && !entity.getImageUrl().isBlank()) {
            return entity.getImageUrl();
        }

        String prompt = entity.getImagePrompt();
        if (prompt == null || prompt.isBlank()) {
            prompt = buildImagePromptFromResult(entity);
        }

        String imageUrl;
        try {
            imageUrl = senseNovaClient.generateImage(prompt);
        } catch (Exception e) {
            log.warn("信息图生成失败: {}", e.getMessage());
            throw new IllegalStateException("信息图生成失败: " + e.getMessage(), e);
        }
        entity.setImageUrl(imageUrl);
        entity.setImagePrompt(prompt);
        repo.save(entity);
        return imageUrl;
    }

    public List<ProsperityPickRecentDTO> recent() {
        LocalDate cutoff = LocalDate.now().minusDays(RECENT_HISTORY_DAYS - 1L);
        return repo.findTop30ByAnalysisDateGreaterThanEqualOrderByAnalysisDateDescIdDesc(cutoff).stream()
                .filter(e -> e.getDegraded() == null || e.getDegraded() != 1)
                .map(this::toRecentDTO)
                .collect(Collectors.toList());
    }

    private ProsperityPickRecentDTO toRecentDTO(InvestProsperityPick entity) {
        JsonNode root = readAnalysis(entity.getResultJson());
        JsonNode summary = root.path("summary");
        List<String> bullets = new ArrayList<>();
        JsonNode bulletNode = summary.path("bullets");
        if (bulletNode.isArray()) {
            for (JsonNode node : bulletNode) {
                String text = node.asText("");
                if (!text.isBlank()) bullets.add(text);
                if (bullets.size() >= 3) break;
            }
        }
        return ProsperityPickRecentDTO.builder()
                .id(entity.getId())
                .stockCode(entity.getStockCode())
                .stockName(entity.getStockName())
                .analysisDate(entity.getAnalysisDate())
                .imageUrl(entity.getImageUrl())
                .summaryOneLiner(summary.path("oneLiner").asText(""))
                .summaryBullets(bullets)
                .valuationVerdict(root.path("valuation").path("verdict").asText(""))
                .technicalVerdict(root.path("technical").path("verdict").asText(""))
                .capitalVerdict(root.path("capital").path("verdict").asText(""))
                .degraded(entity.getDegraded() != null && entity.getDegraded() == 1)
                .build();
    }

    public ProsperityPickResultDTO get(Long id) {
        InvestProsperityPick entity = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("分析记录不存在: " + id));
        TradeStockBasic basic = stockQueryService.resolveStock(entity.getStockCode())
                .orElseGet(() -> {
                    TradeStockBasic b = new TradeStockBasic();
                    b.setStockCode(entity.getStockCode());
                    b.setStockName(entity.getStockName());
                    return b;
                });
        return toResultDTO(entity, basic, true);
    }

    // ============ 内部工具 ============

    private ProsperityPickResultDTO.Profile buildProfile(TradeStockBasic basic) {
        ProsperityPickResultDTO.Profile.ProfileBuilder pb = ProsperityPickResultDTO.Profile.builder()
                .stockCode(basic.getStockCode())
                .stockName(basic.getStockName())
                .exchange(basic.getExchange())
                .board(StockQueryService.deriveBoard(basic.getStockCode()))
                .industry(basic.getSectorNames())
                .peTtm(basic.getPeTtm())
                .pb(basic.getPb())
                .psTtm(basic.getPsTtm());

        // 实时价格 + 总市值
        Optional<TradeStockDaily> dailyOpt = dailyRepo.findFirstByStockCodeOrderByTradeDateDesc(basic.getStockCode());
        dailyOpt.ifPresent(d -> {
            pb.currentPrice(d.getClosePrice());
            if (d.getClosePrice() != null && basic.getTotalShares() != null) {
                BigDecimal cap = d.getClosePrice()
                        .multiply(BigDecimal.valueOf(basic.getTotalShares()))
                        .divide(BigDecimal.valueOf(100_000_000L), 2, RoundingMode.HALF_UP);
                pb.totalMarketCap(cap);
            }
        });

        // 最新财务
        List<TradeStockFinancial> fin = financialRepo
                .findByStockCodeOrderByReportDateDesc(basic.getStockCode());
        if (!fin.isEmpty()) {
            TradeStockFinancial latest = fin.get(0);
            pb.latestReportDate(latest.getReportDate() != null ? latest.getReportDate().toString() : null);
            pb.latestRevenue(formatYi(latest.getRevenue()));
            pb.latestNetProfit(formatYi(latest.getNetProfit()));
        }
        return pb.build();
    }

    private String formatYi(BigDecimal raw) {
        if (raw == null) return null;
        BigDecimal yi = raw.divide(BigDecimal.valueOf(100_000_000L), 2, RoundingMode.HALF_UP);
        return yi + " 亿";
    }

    private String buildPrompt(ProsperityPickResultDTO.Profile profile, TradeStockBasic basic) {
        StringBuilder sb = new StringBuilder();
        sb.append("分析日期: ").append(LocalDate.now()).append("\n");
        sb.append("公司: ").append(profile.getStockName()).append(" ").append(profile.getStockCode()).append(" (A股)\n");
        if (profile.getCurrentPrice() != null) sb.append("现价: ").append(profile.getCurrentPrice()).append(" 元\n");
        if (profile.getTotalMarketCap() != null) sb.append("总市值: ").append(profile.getTotalMarketCap()).append(" 亿元\n");
        if (profile.getIndustry() != null) sb.append("所属行业: ").append(profile.getIndustry()).append("\n");
        if (profile.getPeTtm() != null) sb.append("PE-TTM: ").append(profile.getPeTtm()).append("\n");
        if (profile.getPb() != null) sb.append("PB: ").append(profile.getPb()).append("\n");
        if (profile.getPsTtm() != null) sb.append("PS-TTM: ").append(profile.getPsTtm()).append("\n");

        // 12 季度财务
        List<TradeStockFinancial> records = financialRepo
                .findByStockCodeOrderByReportDateDesc(basic.getStockCode())
                .stream().limit(FINANCIAL_QUARTERS).collect(Collectors.toList());
        if (!records.isEmpty()) {
            sb.append("\n最近 ").append(records.size()).append(" 季度财务（单位：元）：\n");
            sb.append("报告期 | 营收 | 净利润 | 扣非净利润同比 | 毛利率 | 净利率 | ROE\n");
            for (TradeStockFinancial f : records) {
                sb.append(f.getReportDate())
                        .append(" | ").append(safe(f.getRevenue()))
                        .append(" | ").append(safe(f.getNetProfit()))
                        .append(" | ").append(safe(f.getDeductedNetProfitYoy()))
                        .append(" | ").append(safe(f.getGrossMargin()))
                        .append(" | ").append(safe(f.getNetMargin()))
                        .append(" | ").append(safe(f.getRoe()))
                        .append("\n");
            }
        }

        // 联网检索摘要
        if (webSearchClient.isEnabled()) {
            sb.append("\n联网检索摘要:\n");
            appendSearch(sb, profile.getStockName() + " 公司主营业务 董事长 介绍");
            appendSearch(sb, profile.getStockName() + " 所在行业 周期 景气度 2026");
            appendSearch(sb, profile.getStockName() + " 行业政策 十五五 全球");
            appendSearch(sb, profile.getStockName() + " 主力资金 北向资金 龙虎榜");
        } else {
            sb.append("\n（未启用联网检索，请仅基于已知信息和模型自身知识进行分析）\n");
        }

        sb.append("\n请严格按照下方 JSON 格式输出，不要输出任何额外文字、不要使用 markdown：\n");
        sb.append(JSON_SCHEMA);
        return sb.toString();
    }

    private void appendSearch(StringBuilder sb, String query) {
        List<WebSearchClient.SearchResult> rs = webSearchClient.search(query);
        if (rs.isEmpty()) return;
        sb.append("【").append(query).append("】\n");
        for (WebSearchClient.SearchResult r : rs) {
            sb.append(r.toLine()).append("\n");
        }
    }

    private String safe(Object v) {
        return v == null ? "" : v.toString();
    }

    private String extractJson(String raw) {
        if (raw == null) return "{}";
        String s = raw.trim();
        // 去除 ```json ... ``` 包裹
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) s = s.substring(firstNewline + 1);
            int lastFence = s.lastIndexOf("```");
            if (lastFence > 0) s = s.substring(0, lastFence);
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return s.substring(start, end + 1);
        }
        return s;
    }

    private ProsperityPickResultDTO toResultDTO(InvestProsperityPick entity, TradeStockBasic basic, boolean cached) {
        JsonNode analysis = readAnalysis(entity.getResultJson());
        return ProsperityPickResultDTO.builder()
                .id(entity.getId())
                .stockCode(entity.getStockCode())
                .stockName(entity.getStockName())
                .analysisDate(entity.getAnalysisDate())
                .profile(buildProfile(basic))
                .analysis(analysis)
                .imageUrl(entity.getImageUrl())
                .degraded(entity.getDegraded() != null && entity.getDegraded() == 1)
                .cached(cached)
                .build();
    }

    private JsonNode readAnalysis(String resultJson) {
        try {
            return MAPPER.readTree(resultJson == null || resultJson.isBlank() ? "{}" : resultJson);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private String buildImagePromptFromResult(InvestProsperityPick entity) {
        try {
            JsonNode root = MAPPER.readTree(entity.getResultJson() == null ? "{}" : entity.getResultJson());
            JsonNode summary = root.path("summary");
            String oneLiner = summary.path("oneLiner").asText("");
            JsonNode bullets = summary.path("bullets");
            String existing = summary.path("infographicPrompt").asText("");
            if (!existing.isBlank()) return existing;

            StringBuilder bul = new StringBuilder();
            if (bullets.isArray()) {
                int i = 0;
                for (JsonNode b : bullets) {
                    bul.append((char)('①' + i)).append(' ').append(b.asText()).append("；");
                    i++;
                    if (i >= 6) break;
                }
            }
            return "请生成一张以柔和粉色、淡黄色和浅蓝色为主色调的可爱卡通风格信息图（含猫咪、拟人化表情等元素），" +
                    "主题为「" + entity.getStockName() + " " + entity.getStockCode() + " 景气度选股六维分析摘要」，" +
                    "整体排版从左到右分为三个区块：①行业景气度  ②公司基本面与估值  ③技术与资金面。" +
                    "请用图标 + 短句形式呈现以下要点：" + bul + "结论一句话：" + oneLiner +
                    "。包含醒目的主标题与副标题，整体设计有亲和力，信息密度高。";
        } catch (Exception e) {
            return "请生成一张可爱卡通风格信息图，主题为 " + entity.getStockName() + " 景气度选股摘要。";
        }
    }

    // ============ 静态资源 ============

    private static final String SYSTEM_PROMPT =
            "你是一名资深的 A 股价值景气投资分析师，擅长从全球产业趋势、行业周期、国家政策、" +
                    "公司基本面、管理层、估值、技术面、资金面进行全维度分析。" +
                    "请严格按照用户给出的 JSON Schema 输出，不要使用 markdown，" +
                    "不要输出任何解释或前后多余文字，输出必须是合法的 JSON。";

    private static final String JSON_SCHEMA = """
            {
              "industry": {
                "cyclePosition": "上行/下行 + 描述当前所处位置",
                "lastCycleReview": "上一轮完整周期时长、顶底特征以及对比当前位置",
                "next12mForecast": "未来12个月拐点核心触发条件、向上/向下概率与弹性",
                "entryBarrier": "高/中/低，并说明新进入者难易度与现有竞争者增减情况",
                "lifeStage": "导入期/成长期/成熟期/萎缩期",
                "competition": "CR5 市场份额数据 + 公司行业地位",
                "globalResonance": "美/德/日/意/加/印/俄/英 主要国家共振程度与政策支持度"
              },
              "company": {
                "businessMix": "各业务线及其营收占比，新增长曲线",
                "quarterly12": "近12季度营收/归母/扣非净利润 同比环比 + 驱动因子拆分",
                "next2yDriver": "未来2年业绩驱动因素（产能、市占率、提价、成本、新品、海外）",
                "moat": "护城河（品牌/技术/成本/渠道/牌照/规模），可持续性与被颠覆风险",
                "policyFit": "是否国家重点扶持，与十五五规划相关度",
                "globalization": "海外营收过去3年占比走势",
                "priceTrend": "过去1年产品/服务价格变化以及未来1年走势",
                "chairman": "董事长年龄/学历/经历/专业度/企业家精神",
                "catalysts": "概念、故事、股价催化剂"
              },
              "valuation": {
                "type": "成长型/强周期/成熟稳定/亏损或周期底部",
                "methods": [
                  {"name":"PEG/PE/PB/PS/EV-EBITDA/DCF/股息率等","current":"当前值","reasonable":"合理区间","verdict":"便宜/合理/略贵/泡沫"}
                ],
                "verdict": "便宜/合理/高估/泡沫",
                "target2026": "x~y 元",
                "target2027": "x~y 元",
                "reasoning": "估值结论的关键依据"
              },
              "technical": {
                "trendLine": "趋势线判断",
                "ma": "均线判断（多头/空头/纠缠）",
                "volume": "成交量判断",
                "macd": "MACD 信号（金叉/死叉/背离）",
                "verdict": "上升趋势/下降趋势/震荡趋势"
              },
              "capital": {
                "mainNetIn": "主力净流入 5/10/20 日数据或定性",
                "northbound": "北向资金动向",
                "dragonTiger": "龙虎榜信号",
                "verdict": "看好/分歧/谨慎"
              },
              "summary": {
                "bullets": ["要点1", "要点2", "要点3", "要点4", "要点5"],
                "oneLiner": "一句话总体结论",
                "infographicPrompt": "用于生成信息图的中文 prompt（≤500字，柔和粉/黄/蓝色调，可爱卡通风格）"
              }
            }
            """;

    private String mockResultJson(TradeStockBasic basic) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode industry = root.putObject("industry");
        industry.put("cyclePosition", "演示数据：上行中段，景气度持续向上");
        industry.put("lastCycleReview", "演示数据：上一轮周期约 5 年，顶部呈现高估值高增长");
        industry.put("next12mForecast", "演示数据：未来12个月向上概率 60%，弹性 +20%~30%");
        industry.put("entryBarrier", "演示数据：中-高，技术与渠道形成壁垒");
        industry.put("lifeStage", "成长期");
        industry.put("competition", "演示数据：CR5 = 60%，公司位列前三");
        industry.put("globalResonance", "演示数据：欧美日韩共振中，全球政策支持度高");

        ObjectNode company = root.putObject("company");
        company.put("businessMix", "演示数据：主业占比 70%，新业务 30% 高增长");
        company.put("quarterly12", "演示数据：营收同比 15%~25%，净利润高于营收增速");
        company.put("next2yDriver", "演示数据：产能扩张 + 海外拓展 + 新品放量");
        company.put("moat", "演示数据：技术 + 规模效应共筑护城河");
        company.put("policyFit", "演示数据：与十五五规划相关度高");
        company.put("globalization", "演示数据：海外营收占比从 20% 提升至 35%");
        company.put("priceTrend", "演示数据：过去1年价格平稳，未来1年小幅上涨");
        company.put("chairman", "演示数据：董事长经验丰富，专注主业");
        company.put("catalysts", "演示数据：行业景气度提升 + 政策利好 + 新品发布");

        ObjectNode val = root.putObject("valuation");
        val.put("type", "成长型");
        com.fasterxml.jackson.databind.node.ArrayNode methods = val.putArray("methods");
        ObjectNode m1 = methods.addObject();
        m1.put("name", "PEG"); m1.put("current", "1.4"); m1.put("reasonable", "0.8-1.2"); m1.put("verdict", "略贵");
        ObjectNode m2 = methods.addObject();
        m2.put("name", "PS"); m2.put("current", "5.0"); m2.put("reasonable", "3-5"); m2.put("verdict", "合理");
        ObjectNode m3 = methods.addObject();
        m3.put("name", "DCF"); m3.put("current", "估值上限 60"); m3.put("reasonable", "50-65"); m3.put("verdict", "合理");
        val.put("verdict", "合理偏高");
        val.put("target2026", "演示数据 60~70 元");
        val.put("target2027", "演示数据 75~90 元");
        val.put("reasoning", "演示数据：行业景气 + 业绩高增长支撑估值");

        ObjectNode tech = root.putObject("technical");
        tech.put("trendLine", "演示数据：趋势线向上");
        tech.put("ma", "演示数据：5/10/20 日均线多头排列");
        tech.put("volume", "演示数据：温和放量");
        tech.put("macd", "演示数据：日线金叉");
        tech.put("verdict", "上升趋势");

        ObjectNode capital = root.putObject("capital");
        capital.put("mainNetIn", "演示数据：5 日 +1.2 亿，10 日 +3.5 亿");
        capital.put("northbound", "演示数据：北向资金近期持续增持");
        capital.put("dragonTiger", "演示数据：机构净买入");
        capital.put("verdict", "看好");

        ObjectNode summary = root.putObject("summary");
        com.fasterxml.jackson.databind.node.ArrayNode bullets = summary.putArray("bullets");
        bullets.add("行业处于上行中段，景气度持续向上");
        bullets.add("公司业务结构良好，新增长曲线显现");
        bullets.add("估值合理偏高，需关注业绩兑现节奏");
        bullets.add("技术面多头排列，趋势向上");
        bullets.add("主力资金持续净流入，看好后市");
        summary.put("oneLiner", "演示数据：行业 + 公司 + 估值 + 技术 + 资金五维共振，建议关注。");
        summary.put("infographicPrompt", "");

        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return "{}";
        }
    }
}
