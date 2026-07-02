package com.quant.controller;

import com.quant.dto.QueryResultDTO;
import com.quant.service.StockQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/stock")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class StockController {

    private final StockQueryService stockQueryService;
    private final CacheManager cacheManager;
    private final javax.sql.DataSource dataSource;

    @GetMapping("/financial")
    public QueryResultDTO queryFinancial(@RequestParam("keywords") String keywords,
                                         @RequestParam(value = "quarters", required = false) Integer quarters) {
        return stockQueryService.query(keywords, quarters);
    }

    /**
     * 修复 trade_stock_financial 表中年报/Q3 累计营收 -> 单季营收
     * Q3 = Q3累计 - Q2；Q4 = annual - Q1 - Q2 - Q3
     * 注意：Q4 已严重损坏（如药明康德-1357亿）的行，公式 Q4=Q4-Q1-Q2-Q3 会越修越差
     * 需用 /admin/force-fix-field 手动修复
    */
   @PostMapping("/admin/fix-annual-to-quarterly")
    public Map<String, Object> fixAnnualToQuarterly() {
        int updated = stockQueryService.fixAnnualToQuarterlyRevenue();
        var cache = cacheManager.getCache("financial");
        if (cache != null) cache.clear();
        return Map.of("ok", true, "updated", updated, "message", "修复完成，已清空缓存");
    }

    /** 手动清空 revenue_yoy 缓存列，让 app 动态计算 */
    @PostMapping("/admin/clear-yoy-cache")
    public ResponseEntity<?> clearYoyCache(@RequestParam String code, @RequestParam(required = false) Integer year) {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        String sql;
        Object[] params;
        if (year != null) {
            sql = "UPDATE trade_stock_financial SET revenue_yoy = NULL WHERE stock_code = ? AND YEAR(report_date) = ?";
            params = new Object[]{code, year};
        } else {
            sql = "UPDATE trade_stock_financial SET revenue_yoy = NULL WHERE stock_code = ?";
            params = new Object[]{code};
        }
        int updated = jdbc.update(sql, params);
        var cache = cacheManager.getCache("financial");
        if (cache != null) cache.clear();
        return ResponseEntity.ok(Map.of("ok", true, "updated", updated));
    }

    /** 手动修复特定单元值：POST /admin/force-fix-field?code=603259.SH&field=revenue&date=2025-12-31&value=125.99 */
    @PostMapping("/admin/force-fix-field")
    public ResponseEntity<?> forceFixField(@RequestParam String code,
            @RequestParam String field, @RequestParam String date, @RequestParam BigDecimal value) {
        String col = switch (field) {
            case "revenue" -> "revenue";
            case "net_profit" -> "net_profit";
            case "cashflow" -> "operating_cashflow";
            default -> throw new IllegalArgumentException("unknown field: " + field);
        };
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        int updated = jdbc.update(
            "UPDATE trade_stock_financial SET " + col + " = ? WHERE stock_code = ? AND report_date = ?",
            value, code, date);
        var cache = cacheManager.getCache("financial");
        if (cache != null) cache.clear();
        return ResponseEntity.ok(Map.of("ok", true, "updated", updated));
    }

    /** 查询特定股票特定年份的原始 DB 行 */
    @GetMapping("/admin/debug-db-row")
    public ResponseEntity<?> debugDbRow(@RequestParam String code, @RequestParam int year) {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT stock_code, report_date, revenue, net_profit, operating_cashflow, revenue_yoy " +
            "FROM trade_stock_financial WHERE stock_code = ? AND YEAR(report_date) = ? ORDER BY report_date",
            code, year);
        return ResponseEntity.ok(rows);
    }
}
