package com.quant.service;

import com.quant.entity.TechAiQuoteSnapshot;
import com.quant.service.techai.TechAiStockCodeUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class SinaRealtimeQuoteService {

    private static final Charset GBK = Charset.forName("GBK");

    public Map<String, TechAiQuoteSnapshot> fetch(Collection<String> codes) {
        Map<String, TechAiQuoteSnapshot> result = new HashMap<>();
        for (String code : codes) {
            TechAiQuoteSnapshot quote = fetchOne(code);
            if (quote != null && quote.getLatestPrice() != null) {
                result.put(TechAiStockCodeUtils.normalizeProjectCode(quote.getStockCode()), quote);
            }
        }
        return result;
    }

    private TechAiQuoteSnapshot fetchOne(String projectCode) {
        try {
            String body = curl(url(projectCode));
            int first = body.indexOf('"');
            int last = body.lastIndexOf('"');
            if (first < 0 || last <= first) {
                return null;
            }
            String[] parts = body.substring(first + 1, last).split(",", -1);
            if (parts.length < 32) {
                return null;
            }
            BigDecimal latest = decimal(parts[3]);
            if (latest == null || latest.compareTo(BigDecimal.ZERO) <= 0) {
                return null;
            }
            TechAiQuoteSnapshot quote = new TechAiQuoteSnapshot();
            quote.setStockCode(TechAiStockCodeUtils.normalizeProjectCode(projectCode));
            quote.setOpenPrice(decimal(parts[1]));
            quote.setPrevClosePrice(decimal(parts[2]));
            quote.setLatestPrice(latest);
            quote.setVolume(longValue(parts[8]));
            quote.setAmount(decimal(parts[9]));
            quote.setQuoteTime(LocalDateTime.parse(parts[30] + "T" + parts[31]));
            quote.setSource("sina");
            return quote;
        } catch (Exception e) {
            log.warn("Sina realtime quote failed [{}]: {}", projectCode, e.getMessage());
            return null;
        }
    }

    private String url(String projectCode) {
        String normalized = TechAiStockCodeUtils.normalizeProjectCode(projectCode);
        int dot = normalized.indexOf('.');
        String code = normalized.substring(0, dot);
        String market = normalized.substring(dot + 1);
        return "https://hq.sinajs.cn/list=" + market + code;
    }

    private String curl(String url) throws Exception {
        Process process = new ProcessBuilder(
                "curl",
                "-fsSL",
                "--max-time", "6",
                "-H", "Referer: https://finance.sina.com.cn",
                url
        ).start();
        boolean done = process.waitFor(7, TimeUnit.SECONDS);
        if (!done) {
            process.destroyForcibly();
            return "";
        }
        String stdout = new String(process.getInputStream().readAllBytes(), GBK);
        String stderr = new String(process.getErrorStream().readAllBytes(), GBK);
        if (process.exitValue() != 0) {
            throw new IllegalStateException(stderr.isBlank() ? "curl exited " + process.exitValue() : stderr.trim());
        }
        return stdout;
    }

    private BigDecimal decimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long longValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
