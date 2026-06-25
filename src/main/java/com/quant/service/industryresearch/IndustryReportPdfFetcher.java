package com.quant.service.industryresearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 投研报告 PDF 爬取 + 文本提取。
 *
 * 数据流：
 *   1) Tavily 搜 "{keyword} 研报 PDF" 类查询，拿到一批 PDF 链接
 *   2) 用 PDFBox 下载 + 提取文本
 *   3) 截前 N 字汇总成 report_texts 上下文，喂给 IndustryReportReader
 *
 * 兜底：Tavily 失败 / PDF 下载失败 / 提取为空时回退到空上下文，
 *      让流水线继续走，LLM 仍然能基于行业知识给出结构化结论。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndustryReportPdfFetcher {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AiProperties aiProps;
    private final IndustryResearchProperties props;
    private final RestTemplate restTemplate = new RestTemplate();

    private static final String UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    /**
     * 抓取并解析行业研报 PDF，返回结构化结果。
     *
     * @return Map 包含 keyword / fetchedAt / reports / reportTexts / source
     */
    public Map<String, Object> fetchAndDigest(String keyword) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keyword", keyword);
        out.put("fetchedAt", new Date().toString());

        IndustryResearchProperties.PdfFetch cfg = props.getPdfFetch();
        if (!cfg.isEnabled()) {
            out.put("reports", List.of());
            out.put("reportTexts", "");
            out.put("source", "disabled");
            return out;
        }

        // 1. Tavily 搜 PDF URL
        List<String> pdfUrls = searchPdfUrls(keyword, cfg.getMaxResults());
        log.info("[PdfFetcher] Tavily 搜到 {} 个 PDF 链接 (keyword={})", pdfUrls.size(), keyword);

        if (pdfUrls.isEmpty()) {
            out.put("reports", List.of());
            out.put("reportTexts", "");
            out.put("source", "tavily-empty");
            return out;
        }

        // 2. 下载 + PDFBox 提文本
        List<Map<String, Object>> reports = new ArrayList<>();
        StringBuilder textBuf = new StringBuilder();
        int maxReports = Math.min(cfg.getMaxReports(), pdfUrls.size());
        int maxChars = cfg.getMaxReportChars();
        int perReportMax = cfg.getPerReportMaxChars();

        for (int i = 0; i < maxReports; i++) {
            String url = pdfUrls.get(i);
            try {
                Map<String, Object> r = downloadAndExtract(url, perReportMax);
                if (r == null) continue;
                reports.add(r);
                if (textBuf.length() < maxChars) {
                    int remain = maxChars - textBuf.length();
                    String snippet = String.valueOf(r.get("text"));
                    if (snippet.length() > remain) snippet = snippet.substring(0, remain) + "...";
                    textBuf.append("=== 研报 ").append(i + 1).append(" | ")
                            .append(r.get("title")).append(" ===\n");
                    textBuf.append("URL: ").append(url).append("\n");
                    textBuf.append(snippet).append("\n\n");
                }
            } catch (Exception e) {
                log.warn("[PdfFetcher] 解析 PDF 失败 url={}: {}", url, e.getMessage());
            }
        }

        out.put("reports", reports);
        out.put("reportTexts", textBuf.toString());
        out.put("source", "tavily+pdfbox");
        log.info("[PdfFetcher] 成功解析 {}/{} 篇研报 (总文本 {} 字)",
                reports.size(), pdfUrls.size(), textBuf.length());
        return out;
    }

    /**
     * 用 Tavily 搜 ".pdf" 结尾的研报链接。
     */
    private List<String> searchPdfUrls(String keyword, int maxResults) {
        if (!aiProps.getTavily().isEnabled()) return List.of();
        try {
            String url = aiProps.getTavily().getBaseUrl() + "/search";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + aiProps.getTavily().getApiKey());

            // 搜两个 query 拼起来，命中率更高
            String q1 = keyword + " 行业 深度 研报 2025 filetype:pdf";
            Map<String, Object> body1 = new LinkedHashMap<>();
            body1.put("query", q1);
            body1.put("max_results", maxResults);
            body1.put("topic", "general");
            body1.put("include_raw_content", false);
            body1.put("include_answer", false);

            HttpEntity<Map<String, Object>> req = new HttpEntity<>(body1, headers);
            @SuppressWarnings("rawtypes")
            Map resp = restTemplate.postForObject(url, req, Map.class);
            if (resp == null) return List.of();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) resp.getOrDefault("results", List.of());
            return results.stream()
                    .map(r -> String.valueOf(r.getOrDefault("url", "")))
                    .filter(u -> u.toLowerCase().endsWith(".pdf"))
                    .limit(maxResults)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[PdfFetcher] Tavily 搜索失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 下载 PDF 并提取文本。失败返回 null。
     */
    private Map<String, Object> downloadAndExtract(String urlStr, int perReportMax) throws IOException {
        // 准备临时目录
        Path cacheDir = Paths.get("uploads", "industry-research", "pdf-cache");
        Files.createDirectories(cacheDir);

        // URL → 本地文件名
        String safeName = urlStr.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safeName.length() > 200) safeName = safeName.substring(safeName.length() - 200);
        Path local = cacheDir.resolve(safeName);
        if (!Files.exists(local)) {
            try {
                ResponseEntity<byte[]> r = restTemplate.getForEntity(URI.create(urlStr), byte[].class);
                byte[] data = r.getBody();
                if (data == null || data.length < 1024) return null;  // < 1KB 视为失败
                Files.write(local, data);
            } catch (Exception e) {
                log.warn("[PdfFetcher] 下载失败 url={}: {}", urlStr, e.getMessage());
                return null;
            }
        }

        // PDFBox 解析
        File f = local.toFile();
        String text;
        try (PDDocument doc = PDDocument.load(f)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            text = stripper.getText(doc);
        } catch (Exception e) {
            log.warn("[PdfFetcher] PDFBox 解析失败 file={}: {}", local, e.getMessage());
            return null;
        }
        if (text == null || text.isBlank()) return null;

        // 截前 N 字
        String snippet = text.length() > perReportMax
                ? text.substring(0, perReportMax) + "..."
                : text;

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("url", urlStr);
        r.put("title", safeName);
        r.put("text", snippet);
        r.put("length", text.length());
        r.put("source", "pdfbox");
        return r;
    }
}
