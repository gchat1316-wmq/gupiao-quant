package com.quant.service.potential;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.quant.entity.TechAiQuoteSnapshot;
import com.quant.entity.TradeStockBasic;
import com.quant.entity.TradeStockRealtimeKline;
import com.quant.entity.TradeStockRealtimeQuote;
import com.quant.repository.TechAiQuoteSnapshotRepository;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.repository.TradeStockRealtimeKlineRepository;
import com.quant.repository.TradeStockRealtimeQuoteRepository;
import com.quant.service.aistockdata.BaostockMinuteQuoteService;
import com.quant.service.aistockdata.EastMoneyRealtimeQuoteService;
import com.quant.service.aistockdata.SinaRealtimeQuoteService;
import com.quant.service.techai.TechAiStockCodeUtils;

import lombok.RequiredArgsConstructor;

/**
 * 潜力监控 · 行情聚合。
 *
 * <p>按"快照→实时行情→实时5分K→新浪→东财→BaoStock 5分钟"的优先级逐级回退拉取 {@link TechAiQuoteSnapshot}。 同时封装股票代码归一化、股票基础信息按
 * code 候选匹配。
 */
@Component
@RequiredArgsConstructor
public class PotentialQuoteAggregator {

  private final TechAiQuoteSnapshotRepository quoteRepository;
  private final TradeStockRealtimeQuoteRepository realtimeQuoteRepository;
  private final TradeStockRealtimeKlineRepository realtimeKlineRepository;
  private final TradeStockBasicRepository basicRepository;
  private final SinaRealtimeQuoteService sinaRealtimeQuoteService;
  private final EastMoneyRealtimeQuoteService eastMoneyRealtimeQuoteService;
  private final BaostockMinuteQuoteService baostockMinuteQuoteService;

  /** 多源获取最新行情快照。返回的 key 为 project code（无 qmt 后缀）。 */
  public Map<String, TechAiQuoteSnapshot> latestQuotes(Collection<String> codes) {
    if (codes.isEmpty()) {
      return Map.of();
    }
    List<String> normalizedCodes =
        codes.stream().map(TechAiStockCodeUtils::normalizeProjectCode).distinct().toList();
    List<String> candidates = codeCandidates(normalizedCodes);
    Map<String, TechAiQuoteSnapshot> result = new HashMap<>();

    for (TechAiQuoteSnapshot quote : quoteRepository.findLatestByStockCodes(candidates)) {
      putNewer(result, quote);
    }

    List<String> missing = missingCodes(normalizedCodes, result);
    if (!missing.isEmpty()) {
      for (TradeStockRealtimeQuote quote :
          realtimeQuoteRepository.findByStockCodeIn(codeCandidates(missing))) {
        putIfMissing(result, quoteToSnapshot(quote));
      }
    }

    missing = missingCodes(normalizedCodes, result);
    if (!missing.isEmpty()) {
      for (TradeStockRealtimeKline kline :
          realtimeKlineRepository.findLatestByStockCodesAndPeriod(codeCandidates(missing), "5m")) {
        putIfMissing(result, klineToSnapshot(kline));
      }
    }

    missing = missingCodes(normalizedCodes, result);
    if (!missing.isEmpty()) {
      sinaRealtimeQuoteService.fetch(missing).values().forEach(q -> putIfMissing(result, q));
    }

    missing = missingCodes(normalizedCodes, result);
    if (!missing.isEmpty()) {
      eastMoneyRealtimeQuoteService.fetch(missing).values().forEach(q -> putIfMissing(result, q));
    }

    missing = missingCodes(normalizedCodes, result);
    if (!missing.isEmpty()) {
      baostockMinuteQuoteService
          .fetchLatest5m(missing)
          .values()
          .forEach(q -> putIfMissing(result, q));
    }

    return result;
  }

  /** 单只股票基础信息（按 project/qmt/无后缀 三种 candidate 顺序查）。 */
  public TradeStockBasic basic(String stockCode) {
    for (String candidate : codeCandidates(List.of(stockCode))) {
      Optional<TradeStockBasic> basic = basicRepository.findByStockCode(candidate);
      if (basic.isPresent()) {
        return basic.get();
      }
    }
    return null;
  }

  /** 多只股票基础信息映射（key 为归一化 project code）。 */
  public Map<String, TradeStockBasic> basics(Collection<String> codes) {
    Map<String, TradeStockBasic> result = new HashMap<>();
    for (TradeStockBasic basic : basicRepository.findByStockCodeIn(codeCandidates(codes))) {
      result.put(TechAiStockCodeUtils.normalizeProjectCode(basic.getStockCode()), basic);
    }
    return result;
  }

  /** 从 {@link #basics} map 取基础信息。 */
  public TradeStockBasic basicFromMap(Map<String, TradeStockBasic> basics, String stockCode) {
    return basics.get(TechAiStockCodeUtils.normalizeProjectCode(stockCode));
  }

  /** 解析用户输入关键字：先归一化查 DB；查不到且关键字不含 6 位数字时，按名称模糊匹配取第一条。 */
  public String resolveStockCode(String keyword) {
    String normalized = TechAiStockCodeUtils.normalizeProjectCode(keyword);
    TradeStockBasic exact = basic(normalized);
    if (exact != null) {
      return normalized;
    }
    if (!keyword.matches(".*\\d{6}.*")) {
      List<TradeStockBasic> byName = basicRepository.findByStockNameLike(keyword);
      if (!byName.isEmpty()) {
        return TechAiStockCodeUtils.normalizeProjectCode(byName.get(0).getStockCode());
      }
    }
    return normalized;
  }

  List<String> missingCodes(List<String> codes, Map<String, TechAiQuoteSnapshot> quotes) {
    return codes.stream().filter(code -> !quotes.containsKey(code)).toList();
  }

  void putNewer(Map<String, TechAiQuoteSnapshot> quotes, TechAiQuoteSnapshot quote) {
    if (quote == null || quote.getStockCode() == null || quote.getLatestPrice() == null) {
      return;
    }
    String key = TechAiStockCodeUtils.normalizeProjectCode(quote.getStockCode());
    TechAiQuoteSnapshot existing = quotes.get(key);
    if (existing == null
        || existing.getQuoteTime() == null
        || (quote.getQuoteTime() != null
            && quote.getQuoteTime().isAfter(existing.getQuoteTime()))) {
      quotes.put(key, quote);
    }
  }

  void putIfMissing(Map<String, TechAiQuoteSnapshot> quotes, TechAiQuoteSnapshot quote) {
    if (quote == null || quote.getStockCode() == null || quote.getLatestPrice() == null) {
      return;
    }
    quotes.putIfAbsent(TechAiStockCodeUtils.normalizeProjectCode(quote.getStockCode()), quote);
  }

  static TechAiQuoteSnapshot quoteToSnapshot(TradeStockRealtimeQuote source) {
    TechAiQuoteSnapshot quote = new TechAiQuoteSnapshot();
    quote.setStockCode(source.getStockCode());
    quote.setQuoteTime(source.getQuoteTime());
    quote.setLatestPrice(source.getLatestPrice());
    quote.setPrevClosePrice(source.getLastClose());
    quote.setOpenPrice(source.getOpenPrice());
    quote.setVolume(source.getVolume());
    quote.setAmount(source.getAmount());
    quote.setTurnoverRate(source.getTurnoverRate());
    quote.setMinute5Time(source.getKlineTime5m());
    quote.setSource("realtime");
    return quote;
  }

  static TechAiQuoteSnapshot klineToSnapshot(TradeStockRealtimeKline source) {
    TechAiQuoteSnapshot quote = new TechAiQuoteSnapshot();
    quote.setStockCode(source.getStockCode());
    quote.setQuoteTime(source.getKlineTime());
    quote.setLatestPrice(source.getClosePrice());
    quote.setPrevClosePrice(source.getPreClose());
    quote.setOpenPrice(source.getOpenPrice());
    quote.setVolume(source.getVolume());
    quote.setAmount(source.getAmount());
    quote.setTurnoverRate(source.getTurnoverRate());
    quote.setMinute5OpenPrice(source.getOpenPrice());
    quote.setMinute5Time(source.getKlineTime());
    quote.setSource("realtime_5m");
    return quote;
  }

  /** 一组 code 的所有查询候选：project 码 + qmt 后缀码 + 无后缀码。 */
  List<String> codeCandidates(Collection<String> codes) {
    List<String> result = new ArrayList<>();
    for (String code : codes) {
      String normalized = TechAiStockCodeUtils.normalizeProjectCode(code);
      result.add(normalized);
      result.add(TechAiStockCodeUtils.toQmtCode(normalized));
      int dot = normalized.indexOf('.');
      if (dot > 0) {
        result.add(normalized.substring(0, dot));
      }
    }
    return result.stream().distinct().toList();
  }
}
