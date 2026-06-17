package com.quant.service.prosperitystrong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.config.ProsperityStrongProperties;
import com.quant.entity.ProsperityHotSector;
import com.quant.entity.ProsperityLeaderCandidate;
import com.quant.entity.TradeStockBasic;
import com.quant.entity.TradeStockDaily;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.repository.TradeStockDailyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Step 2: 龙头识别
 *
 * 在板块成分股内按 (年度涨幅 + 5日涨幅 + 换手率) 加权评分,
 * 并剔除 ST / 次新股(<1年)。不使用停牌字段过滤,该数据源不稳定。
 *
 * 为简化首阶段,从 TradeStockBasic.sectorNames 字段中模糊匹配板块名称,
 * 找出该板块内的成分股进行评分。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LeaderIdentifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String EM_MEMBER_URL =
            "https://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=200&po=1&np=1&ut=bd1d9ddb04089700cf9c27f6f7426281"
                    + "&fltt=2&invt=2&fid=f3&fs=b:%s&fields=f12,f14";

    private final TradeStockBasicRepository basicRepo;
    private final TradeStockDailyRepository dailyRepo;
    private final ProsperityStrongProperties props;

    public List<ProsperityLeaderCandidate> identify(LocalDate snapDate, ProsperityHotSector sector) {
        if (sector == null || sector.getSectorName() == null) return Collections.emptyList();

        MemberLookup memberLookup = lookupMembers(sector);
        List<TradeStockBasic> realMembers = memberLookup.members();
        if (realMembers.size() > 100) {
            realMembers = realMembers.subList(0, 100);
        }
        if (realMembers.isEmpty()) {
            log.info("板块[{}] 未找到成分股,跳过: {}", sector.getSectorName(), memberLookup.diagnosticMessage());
            return Collections.emptyList();
        }

        Map<String, TradeStockDaily> latestQuotes = new HashMap<>();
        for (TradeStockDaily d : dailyRepo.findLatestByStockCodes(
                realMembers.stream().map(TradeStockBasic::getStockCode).toList())) {
            latestQuotes.put(d.getStockCode(), d);
        }
        LocalDate yearStart = LocalDate.of(snapDate.getYear(), 1, 1);
        Map<String, TradeStockDaily> yearStartQuotes = new HashMap<>();
        for (TradeStockDaily d : dailyRepo.findFirstAfterDateByStockCodes(
                realMembers.stream().map(TradeStockBasic::getStockCode).toList(), yearStart)) {
            yearStartQuotes.put(d.getStockCode(), d);
        }

        List<ProsperityLeaderCandidate> scored = new ArrayList<>();
        for (TradeStockBasic basic : realMembers) {
            TradeStockDaily latest = latestQuotes.get(basic.getStockCode());
            if (latest == null) continue;

            String filterReason = passFastFilter(basic, latest, snapDate);
            BigDecimal ytdChange = ytdChange(latest, yearStartQuotes.get(basic.getStockCode()));
            BigDecimal turnover = latest.getTurnoverRate();
            // 简化的 5 日涨幅:用最近6条记录里的第一/最后一条
            List<TradeStockDaily> last6 = dailyRepo.findTop6ByStockCodeOrderByTradeDateDesc(basic.getStockCode());
            BigDecimal change5d = null;
            if (last6.size() >= 5) {
                BigDecimal cur = last6.get(0).getClosePrice();
                BigDecimal old = last6.get(last6.size() - 1).getClosePrice();
                if (cur != null && old != null && old.compareTo(BigDecimal.ZERO) > 0) {
                    change5d = cur.subtract(old).divide(old, 6, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100));
                }
            }

            ProsperityLeaderCandidate cand = new ProsperityLeaderCandidate();
            cand.setSnapDate(snapDate);
            cand.setSectorId(sector.getId() == null ? 0 : sector.getId());
            cand.setSectorName(sector.getSectorName());
            cand.setStockCode(basic.getStockCode());
            cand.setStockName(basic.getStockName());
            cand.setYtdChange(ytdChange);
            cand.setChange5d(change5d);
            cand.setTurnoverRate(turnover);
            cand.setLeaderScore(score(ytdChange, change5d, turnover));
            cand.setFilterPassed(filterReason == null ? 1 : 0);
            cand.setFilterReason(filterReason);
            scored.add(cand);
        }

        scored.sort(Comparator.comparing(ProsperityLeaderCandidate::getLeaderScore,
                Comparator.nullsLast(Comparator.reverseOrder())));

        int limit = Math.max(props.getLeadersPerSector(), props.getLeadersPerSector() * 4);
        if (scored.size() > limit) {
            return new ArrayList<>(scored.subList(0, limit));
        }
        return scored;
    }

    private MemberLookup lookupMembers(ProsperityHotSector sector) {
        String sectorName = sector == null ? null : sector.getSectorName();
        if (sectorName == null || sectorName.isBlank()) {
            return new MemberLookup(List.of(), "板块名称为空");
        }

        String emDiagnostic = null;
        if (canUseEastMoneyMembers(sector)) {
            try {
                List<TradeStockBasic> membersByCode = findMembersByEastMoneySectorCode(sector.getSectorCode());
                if (!membersByCode.isEmpty()) {
                    return new MemberLookup(membersByCode, "已通过东方财富板块成分股匹配本地股票");
                }
                emDiagnostic = "东方财富板块成分股为空或未映射到本地股票";
            } catch (Exception e) {
                log.warn("板块[{}] 东方财富成分股抓取失败: {}", sectorName, e.getMessage());
                emDiagnostic = "东方财富板块成分股抓取失败: " + e.getMessage();
            }
        }

        List<TradeStockBasic> localMembers = findMembersByAliases(sectorName);
        if (!localMembers.isEmpty()) {
            String diagnostic = emDiagnostic == null
                    ? "已通过本地 trade_stock_basic.sector_names/别名匹配成分股"
                    : emDiagnostic + "；已回退到本地 trade_stock_basic.sector_names/别名匹配成分股";
            return new MemberLookup(localMembers, diagnostic);
        }

        String diagnostic = emDiagnostic == null
                ? "本地 trade_stock_basic.sector_names 未匹配到该板块或别名"
                : emDiagnostic + "；本地 trade_stock_basic.sector_names 未匹配到该板块或别名";
        return new MemberLookup(List.of(), diagnostic);
    }

    private boolean canUseEastMoneyMembers(ProsperityHotSector sector) {
        return sector != null
                && sector.getSectorCode() != null
                && sector.getSectorCode().startsWith("BK");
    }

    private List<TradeStockBasic> findMembersByEastMoneySectorCode(String sectorCode) throws Exception {
        Map<String, TradeStockBasic> members = new LinkedHashMap<>();
        for (String rawCode : fetchEastMoneyMemberCodes(sectorCode)) {
            for (TradeStockBasic basic : basicRepo.findByStockCodePrefix(rawCode)) {
                members.putIfAbsent(basic.getStockCode(), basic);
            }
        }
        return new ArrayList<>(members.values());
    }

    List<String> fetchEastMoneyMemberCodes(String sectorCode) throws Exception {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeoutMs = props.getSource().getTimeoutSeconds() * 1000;
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        RestTemplate rest = new RestTemplate(factory);

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.USER_AGENT, "Mozilla/5.0");
        headers.add(HttpHeaders.REFERER, "https://quote.eastmoney.com/");

        String url = String.format(EM_MEMBER_URL, sectorCode);
        ResponseEntity<String> response = rest.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
        String body = response.getBody();
        if (body == null || body.isBlank()) {
            return List.of();
        }
        JsonNode root = MAPPER.readTree(body);
        JsonNode diff = root.path("data").path("diff");
        if (!diff.isArray() || diff.isEmpty()) {
            return List.of();
        }

        List<String> codes = new ArrayList<>();
        for (JsonNode item : diff) {
            String rawCode = item.path("f12").asText("").trim();
            if (rawCode.matches("\\d{6}")) {
                codes.add(rawCode);
            }
        }
        return codes.stream().distinct().toList();
    }

    private List<TradeStockBasic> findMembersByAliases(String sectorName) {
        Map<String, TradeStockBasic> members = new LinkedHashMap<>();
        for (String keyword : sectorKeywords(sectorName)) {
            for (TradeStockBasic basic : basicRepo.findBySectorNameLike(keyword)) {
                members.putIfAbsent(basic.getStockCode(), basic);
            }
        }
        return new ArrayList<>(members.values());
    }

    private List<String> sectorKeywords(String sectorName) {
        List<String> keywords = new ArrayList<>();
        String normalized = normalizeSectorName(sectorName);
        if (sectorName != null && !sectorName.isBlank()) {
            keywords.add(sectorName.trim());
        }
        if (!normalized.isBlank()) {
            keywords.add(normalized);
        }
        switch (normalized) {
            case "半导体" -> {
                keywords.add("芯片");
                keywords.add("集成电路");
                keywords.add("中芯国际");
                keywords.add("华为海思");
            }
            case "半导体及元件" -> {
                keywords.add("芯片");
                keywords.add("集成电路");
                keywords.add("PCB");
                keywords.add("华为海思");
            }
            case "光模块" -> {
                keywords.add("CPO");
                keywords.add("光纤");
                keywords.add("F5G");
            }
            case "AI算力" -> {
                keywords.add("DeepSeek");
                keywords.add("ChatGPT");
                keywords.add("AIGC");
                keywords.add("英伟达");
                keywords.add("CPO");
            }
            case "工业母机" -> {
                keywords.add("机器人");
                keywords.add("智能机器");
            }
            case "创新药" -> {
                keywords.add("医药");
                keywords.add("医疗器械");
                keywords.add("基因");
                keywords.add("CXO");
            }
            default -> {
            }
        }
        return keywords.stream().distinct().toList();
    }

    private String normalizeSectorName(String sectorName) {
        if (sectorName == null) return "";
        return sectorName.replaceAll("[ⅠⅡⅢⅣⅤ]+", "")
                .replace("（", "(")
                .replace("）", ")")
                .replaceAll("\\s+", "")
                .replaceAll("(概念|板块|指数|等权)$", "")
                .trim();
    }

    public MemberStats memberStats(ProsperityHotSector sector) {
        MemberLookup lookup = lookupMembers(sector);
        List<TradeStockBasic> members = lookup.members();
        if (members.isEmpty()) {
            return new MemberStats(0, 0, lookup.diagnosticMessage());
        }
        List<TradeStockDaily> quotes = dailyRepo.findLatestByStockCodes(
                members.stream().map(TradeStockBasic::getStockCode).toList());
        String message = quotes.isEmpty()
                ? lookup.diagnosticMessage() + "；但 trade_stock_daily 无这些股票的日线行情,无法计算龙头分"
                : lookup.diagnosticMessage() + "；已匹配成分股和日线行情";
        return new MemberStats(members.size(), quotes.size(), message);
    }

    private String passFastFilter(TradeStockBasic basic, TradeStockDaily latest, LocalDate snapDate) {
        if (basic.getStockName() != null && basic.getStockName().contains("ST")) return "ST标的";
        if (basic.getListDate() != null && basic.getListDate().isAfter(LocalDate.now().minusYears(1))) {
            return "次新股(上市不足1年)";
        }
        return null;
    }

    private BigDecimal ytdChange(TradeStockDaily latest, TradeStockDaily base) {
        if (latest == null || base == null) return null;
        BigDecimal cur = latest.getClosePrice();
        BigDecimal old = base.getClosePrice();
        if (cur == null || old == null || old.compareTo(BigDecimal.ZERO) == 0) return null;
        return cur.subtract(old).divide(old, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    private BigDecimal score(BigDecimal ytd, BigDecimal d5, BigDecimal turnover) {
        double y = ytd == null ? 0 : Math.min(150, Math.max(-30, ytd.doubleValue())) + 30;
        double f = d5 == null ? 0 : Math.min(20, Math.max(-15, d5.doubleValue())) + 15;
        double t = turnover == null ? 0 : Math.min(15, turnover.doubleValue());
        // 归一化大致到 0-100
        double yScore = y / 180.0 * 100;          // 0-100
        double fScore = f / 35.0 * 100;            // 0-100
        double tScore = t / 15.0 * 100;            // 0-100
        double total = 0.4 * yScore + 0.4 * fScore + 0.2 * tScore;
        return BigDecimal.valueOf(Math.max(0, Math.min(100, total))).setScale(2, RoundingMode.HALF_UP);
    }

    private record MemberLookup(List<TradeStockBasic> members, String diagnosticMessage) {}

    public record MemberStats(int matchedMemberCount, int quotedMemberCount, String diagnosticMessage) {}
}
