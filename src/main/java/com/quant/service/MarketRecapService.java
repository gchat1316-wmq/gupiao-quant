package com.quant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.dto.marketrecap.KeyDataItemDTO;
import com.quant.dto.marketrecap.MarketRecapDetailDTO;
import com.quant.dto.marketrecap.MarketRecapPageDTO;
import com.quant.dto.marketrecap.MarketRecapSummaryDTO;
import com.quant.dto.marketrecap.SectorCardDTO;
import com.quant.dto.marketrecap.StrategyItemDTO;
import com.quant.dto.marketrecap.MarketRecapBadgeDTO;
import com.quant.entity.InvestMarketRecap;
import com.quant.repository.InvestMarketRecapRepository;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class MarketRecapService {

    private final InvestMarketRecapRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Parser markdownParser;
    private final HtmlRenderer htmlRenderer;

    public MarketRecapService(InvestMarketRecapRepository repository) {
        this.repository = repository;
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, List.of(TablesExtension.create()));
        this.markdownParser = Parser.builder(options).build();
        this.htmlRenderer = HtmlRenderer.builder(options).build();
    }

    public List<String> listMarkets() {
        return sortMarkets(repository.findDistinctMarkets());
    }

    public MarketRecapPageDTO getPage(String requestedMarket) {
        List<String> markets = listMarkets();
        String selectedMarket = pickMarket(markets, requestedMarket);
        List<InvestMarketRecap> recaps = selectedMarket == null
                ? List.of()
                : repository.findByMarketOrderByTradeDateDescIdDesc(selectedMarket);

        return MarketRecapPageDTO.builder()
                .markets(markets)
                .selectedMarket(selectedMarket)
                .latest(recaps.isEmpty() ? null : toDetail(recaps.get(0)))
                .timeline(recaps.stream().map(this::toSummary).toList())
                .build();
    }

    public MarketRecapDetailDTO getDetail(Long id) {
        return repository.findById(id)
                .map(this::toDetail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到复盘：" + id));
    }

    public MarketRecapBadgeDTO getBadgeSummary() {
        return getBadgeSummary(LocalDate.now());
    }

    public MarketRecapBadgeDTO getBadgeSummary(LocalDate referenceDate) {
        List<InvestMarketRecap> recaps = repository.findAllByOrderByTradeDateDescIdDesc();
        int today = 0;
        int yesterday = 0;
        Long latestId = null;
        String latestTradeDate = null;

        LocalDate todayDate = referenceDate;
        LocalDate yesterdayDate = referenceDate.minusDays(1);

        for (InvestMarketRecap recap : recaps) {
            LocalDate tradeDate = recap.getTradeDate();
            if (tradeDate == null) {
                continue;
            }
            if (latestId == null) {
                latestId = recap.getId();
                latestTradeDate = formatDate(tradeDate);
            }
            if (tradeDate.equals(todayDate)) {
                today++;
            } else if (tradeDate.equals(yesterdayDate)) {
                yesterday++;
            } else if (tradeDate.isBefore(yesterdayDate)) {
                // 已经超出"今天/昨天"窗口,后面的更早,可以提前结束(已按 tradeDate desc 排序)
                break;
            }
        }

        return MarketRecapBadgeDTO.builder()
                .today(today)
                .yesterday(yesterday)
                .latestId(latestId)
                .latestTradeDate(latestTradeDate)
                .build();
    }

    private List<String> sortMarkets(List<String> markets) {
        return markets.stream()
                .filter(this::hasText)
                .distinct()
                .sorted(Comparator.comparingInt((String market) -> "A股".equals(market) ? 0 : 1)
                        .thenComparing(String::compareTo))
                .toList();
    }

    private String pickMarket(List<String> markets, String requestedMarket) {
        if (hasText(requestedMarket)) {
            return requestedMarket.trim();
        }
        if (markets.contains("A股")) {
            return "A股";
        }
        return markets.isEmpty() ? null : markets.get(0);
    }

    private MarketRecapSummaryDTO toSummary(InvestMarketRecap recap) {
        return MarketRecapSummaryDTO.builder()
                .id(recap.getId())
                .market(recap.getMarket())
                .tradeDate(formatDate(recap.getTradeDate()))
                .title(recap.getTitle())
                .indexesSummary(recap.getIndexesSummary())
                .advanceDecline(recap.getAdvanceDecline())
                .limitUp(recap.getLimitUp())
                .limitDown(recap.getLimitDown())
                .sentiment(recap.getSentiment())
                .summaryExcerpt(buildSummaryExcerpt(recap))
                .build();
    }

    private MarketRecapDetailDTO toDetail(InvestMarketRecap recap) {
        MarketRecapSummaryDTO summary = toSummary(recap);
        return MarketRecapDetailDTO.builder()
                .id(summary.getId())
                .market(summary.getMarket())
                .tradeDate(summary.getTradeDate())
                .title(summary.getTitle())
                .indexesSummary(summary.getIndexesSummary())
                .advanceDecline(summary.getAdvanceDecline())
                .limitUp(summary.getLimitUp())
                .limitDown(summary.getLimitDown())
                .sentiment(summary.getSentiment())
                .summaryExcerpt(summary.getSummaryExcerpt())
                .sectors(parseSectors(recap.getSectors()))
                .risks(parseStringList(recap.getRisks()))
                .catalysts(parseStringList(recap.getCatalysts()))
                .keyData(parseKeyData(recap.getKeyData()))
                .nextDayStrategy(parseStrategy(recap.getNextDayStrategy()))
                .contentHtml(renderMarkdown(recap.getContent()))
                .build();
    }

    private String formatDate(LocalDate date) {
        return date == null ? "" : date.toString();
    }

    private String buildSummaryExcerpt(InvestMarketRecap recap) {
        String source = hasText(recap.getTitle()) ? recap.getTitle() : recap.getContent();
        if (!hasText(source)) {
            return "";
        }
        String normalized = source.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 72 ? normalized : normalized.substring(0, 72) + "...";
    }

    private List<SectorCardDTO> parseSectors(String raw) {
        JsonNode root = readJsonNode(raw);
        if (root == null || !root.isArray()) {
            return List.of();
        }

        List<SectorCardDTO> sectors = new ArrayList<>();
        for (JsonNode node : root) {
            sectors.add(SectorCardDTO.builder()
                    .name(text(node, "name"))
                    .strengthLabel(firstNonBlank(text(node, "涨停数"), text(node, "strength")))
                    .leaders(stringArray(node.path("标的")))
                    .catalyst(readCatalyst(node))
                    .build());
        }
        return sectors;
    }

    private List<String> parseStringList(String raw) {
        JsonNode root = readJsonNode(raw);
        if (root != null && root.isArray()) {
            return stringArray(root);
        }
        if (!hasText(raw)) {
            return List.of();
        }
        return raw.lines()
                .map(String::trim)
                .map(line -> line.replaceFirst("^[-*\\d.、\\s]+", "").trim())
                .filter(this::hasText)
                .toList();
    }

    private List<KeyDataItemDTO> parseKeyData(String raw) {
        JsonNode root = readJsonNode(raw);
        if (root == null) {
            return hasText(raw)
                    ? List.of(KeyDataItemDTO.builder().label("关键数据").value(raw.trim()).build())
                    : List.of();
        }

        List<KeyDataItemDTO> items = new ArrayList<>();
        flattenKeyData("", root, items);
        return items;
    }

    private void flattenKeyData(String prefix, JsonNode node, List<KeyDataItemDTO> items) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> iterator = node.fields();
            while (iterator.hasNext()) {
                Map.Entry<String, JsonNode> entry = iterator.next();
                String label = prefix.isEmpty() ? entry.getKey() : prefix + " / " + entry.getKey();
                flattenKeyData(label, entry.getValue(), items);
            }
            return;
        }
        if (node.isArray()) {
            List<String> values = stringArray(node);
            if (!values.isEmpty()) {
                items.add(KeyDataItemDTO.builder().label(prefix).value(String.join("、", values)).build());
            }
            return;
        }
        items.add(KeyDataItemDTO.builder().label(prefix).value(node.asText("")).build());
    }

    private List<StrategyItemDTO> parseStrategy(String raw) {
        JsonNode root = readJsonNode(raw);
        if (root != null && root.isObject()) {
            List<StrategyItemDTO> strategies = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> iterator = root.fields();
            while (iterator.hasNext()) {
                Map.Entry<String, JsonNode> entry = iterator.next();
                strategies.add(StrategyItemDTO.builder()
                        .label(entry.getKey())
                        .value(entry.getValue().asText(""))
                        .build());
            }
            return strategies;
        }
        if (hasText(raw)) {
            return List.of(StrategyItemDTO.builder().label("策略").value(raw.trim()).build());
        }
        return List.of();
    }

    private String renderMarkdown(String markdown) {
        if (!hasText(markdown)) {
            return "";
        }
        return htmlRenderer.render(markdownParser.parse(markdown));
    }

    private JsonNode readJsonNode(String raw) {
        if (!hasText(raw)) {
            return null;
        }
        String current = raw.trim();
        for (int i = 0; i < 6; i++) {
            try {
                JsonNode node = objectMapper.readTree(current);
                if (node != null && node.isTextual() && looksLikeJson(node.asText())) {
                    current = node.asText().trim();
                    continue;
                }
                return node;
            } catch (Exception ignored) {
                String unwrapped = unwrapJsonString(current);
                if (unwrapped.equals(current)) {
                    break;
                }
                current = unwrapped.trim();
            }
        }
        return null;
    }

    private boolean looksLikeJson(String value) {
        if (!hasText(value)) {
            return false;
        }
        String trimmed = value.trim();
        return (trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"))
                || (trimmed.startsWith("\"") && trimmed.endsWith("\""));
    }

    private String unwrapJsonString(String raw) {
        if (!hasText(raw)) {
            return raw;
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("\"") || !trimmed.endsWith("\"")) {
            return trimmed;
        }
        try {
            return objectMapper.readValue(trimmed, String.class);
        } catch (Exception ignored) {
            return trimmed.substring(1, trimmed.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }
    }

    private String readCatalyst(JsonNode node) {
        JsonNode catalyst = node.path("催化");
        if (catalyst.isArray()) {
            return String.join("、", stringArray(catalyst));
        }
        if (catalyst.isTextual()) {
            return catalyst.asText("");
        }
        return "";
    }

    private List<String> stringArray(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : arrayNode) {
            if (item != null && !item.isNull()) {
                result.add(item.asText(""));
            }
        }
        return result;
    }

    private String text(JsonNode node, String field) {
        return node == null ? "" : node.path(field).asText("");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
