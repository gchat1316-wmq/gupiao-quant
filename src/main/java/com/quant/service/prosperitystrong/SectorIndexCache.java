package com.quant.service.prosperitystrong;

import com.quant.entity.TradeStockBasic;
import com.quant.repository.TradeStockBasicRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 板块 → 成分股 倒排索引 + 关键词搜索结果缓存。
 *
 * <p>痛点：{@code trade_stock_basic.sector_names} 是 text 字段 + LIKE '%xxx%'，
 * 即使加了 FULLTEXT 索引仍然要查 5000+ 行；
 * 而且 ProsperityStrongPipelineService#sectors() 在一次接口调用里
 * 会调 memberStats() 多次（每板块一次），叠加后接口延迟 20+ 秒。</p>
 *
 * <p>方案：
 * <ol>
 *   <li>启动时一次性 SELECT stock_code, sector_names 到内存，做"关键词 → 股票集合"倒排索引</li>
 *   <li>findMembersByAliases 改走纯内存 O(关键词数) 查表</li>
 *   <li>每次查询都先检查关键词缓存（同 keyword 5 分钟内复用结果）</li>
 *   <li>每天凌晨 4 点和午盘 12:30 各重载一次，保证数据新鲜度</li>
 * </ol>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SectorIndexCache {

    private final TradeStockBasicRepository basicRepo;

    /** sectorNames 里出现过的"非空"关键词 → 命中股票代码集合。case-insensitive。 */
    private final ConcurrentHashMap<String, Set<String>> invertedIndex = new ConcurrentHashMap<>();

    /** 最大缓存条目数，超过后 LRU 淘汰最旧条目（2026-07-07 防内存泄漏）。 */
    private static final int KEYWORD_CACHE_MAX = 200;

    /** 关键词 → 命中股票 (Basic 完整对象) 的查询结果缓存，容量上限 200 条 + TTL 5 分钟。 */
    private final Map<String, CacheEntry<List<TradeStockBasic>>> keywordCache =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                private static final long serialVersionUID = 1L;
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry<List<TradeStockBasic>>> eldest) {
                    return size() > KEYWORD_CACHE_MAX;
                }
            });

    /** 当前加载状态。 */
    private final AtomicReference<Status> status = new AtomicReference<>(new Status(0, null));

    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    @PostConstruct
    public void warmUp() {
        try {
            reload();
        } catch (Exception e) {
            log.error("SectorIndexCache 启动加载失败（首次访问会回退到 LIKE 全表扫）: {}", e.getMessage());
        }
    }

    /** 每天凌晨 4:00 + 午盘 12:30 各重载一次，避开交易时段。 */
    @Scheduled(cron = "0 0 4 * * ?")
    public void reloadAt4am() {
        reload();
    }

    @Scheduled(cron = "0 30 12 * * ?")
    public void reloadAt1230() {
        reload();
    }

    @Transactional(readOnly = true)
    public synchronized void reload() {
        long t0 = System.currentTimeMillis();
        List<Object[]> rows = basicRepo.findAllSectorNamesRaw();
        ConcurrentHashMap<String, Set<String>> next = new ConcurrentHashMap<>();
        int total = 0;
        for (Object[] row : rows) {
            String code = (String) row[0];
            String names = (String) row[1];
            if (code == null || names == null || names.isBlank()) continue;
            total++;
            for (String part : names.split(",")) {
                String token = part.trim();
                if (token.isEmpty()) continue;
                // 同时索引原文 + 小写，避免"半导体" vs "ＴＧＮ" 大小写匹配不上
                next.computeIfAbsent(token, k -> ConcurrentHashMap.newKeySet()).add(code);
                String lower = token.toLowerCase(Locale.ROOT);
                if (!lower.equals(token)) {
                    next.computeIfAbsent(lower, k -> ConcurrentHashMap.newKeySet()).add(code);
                }
            }
        }
        invertedIndex.clear();
        invertedIndex.putAll(next);
        keywordCache.clear();
        status.set(new Status(total, System.currentTimeMillis()));
        long cost = System.currentTimeMillis() - t0;
        log.info("SectorIndexCache 加载完成: stock={}, keyword={}, cost={}ms",
                total, next.size(), cost);
    }

    /**
     * 用关键词列表在倒排索引里取并集对应的 stock_code 集合。
     * 多个关键词的命中股票是"任一关键词命中即纳入"，等价于 sector_names LIKE '%kw1%' OR LIKE '%kw2%'。
     */
    public Set<String> findStockCodesByKeywords(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) return Collections.emptySet();
        Set<String> result = null;
        for (String kw : keywords) {
            if (kw == null) continue;
            String key = kw.trim();
            if (key.isEmpty()) continue;
            Set<String> hit = invertedIndex.get(key);
            if (hit == null) {
                // 大小写再试一次
                hit = invertedIndex.get(key.toLowerCase(Locale.ROOT));
            }
            if (hit == null || hit.isEmpty()) continue;
            if (result == null) {
                result = new HashSet<>(hit);
            } else {
                result.addAll(hit);
            }
        }
        return result == null ? Collections.emptySet() : result;
    }

    /**
     * 关键词缓存查询。命中且未过期 → 直接返回缓存的 Basic 列表。
     */
    /**
     * 关键词缓存查询。命中且未过期 → 直接返回缓存的 Basic 列表。
     * 容量上限 200 条，超出后 LRU 淘汰最旧条目（2026-07-07 防内存泄漏）。
     */
    public List<TradeStockBasic> getOrLoadByKeywords(List<String> keywords,
                                                      java.util.function.Supplier<List<TradeStockBasic>> loader) {
        if (keywords == null || keywords.isEmpty()) return List.of();
        String key = String.join("|", keywords.stream().sorted().toList());
        CacheEntry<List<TradeStockBasic>> cached;
        long now = System.currentTimeMillis();
        synchronized (keywordCache) {
            cached = keywordCache.get(key);
        }
        if (cached != null && now - cached.timestamp < CACHE_TTL_MS) {
            return cached.value;
        }
        List<TradeStockBasic> loaded = loader.get();
        synchronized (keywordCache) {
            keywordCache.put(key, new CacheEntry<>(loaded, now));
        }
        return loaded;
    }

    /** 当前缓存状态（用于诊断页） */
    public Status getStatus() {
        return status.get();
    }

    public record Status(int stockCount, Long loadedAt) {}

    private record CacheEntry<V>(V value, long timestamp) {}
}
