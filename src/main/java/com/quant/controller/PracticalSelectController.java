package com.quant.controller;

import com.quant.dto.practicalselect.PracticalSelectResponse;
import com.quant.entity.InvestPracticalSelectRecord;
import com.quant.repository.InvestPracticalSelectRecordRepository;
import com.quant.service.PracticalSelectPdfService;
import com.quant.service.PracticalSelectService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@RestController
@RequestMapping("/api/practical-select")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class PracticalSelectController {

    /** 实战选股同步接口上限 25s，避免上游行情/DB 抖动时连接池被占满导致雪崩。 */
    private static final long ANALYZE_TIMEOUT_SECONDS = 25L;
    /** 异步执行器（专用单线程池，不与 Tomcat 共享，避免互相抢占）。 */
    private static final java.util.concurrent.ExecutorService ANALYZE_EXECUTOR =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "practical-select-analyze");
                t.setDaemon(true);
                return t;
            });

    private final PracticalSelectService service;
    private final PracticalSelectPdfService pdfService;
    private final InvestPracticalSelectRecordRepository recordRepository;

    // ============ 分析 ============

    @PostMapping("/analyze")
    public PracticalSelectResponse analyze(@RequestParam("keyword") String keyword) {
        return analyzeWithTimeout(keyword);
    }

    @GetMapping("/analyze")
    public PracticalSelectResponse analyzeGet(@RequestParam("keyword") String keyword) {
        return analyzeWithTimeout(keyword);
    }

    /**
     * 同步入口包一层超时：上游行情 502 或 DB 连接池被占满时，最迟 25s 内给用户友好提示，
     * 而不是让 HTTP 连接挂死、HikariCP 雪崩升级。
     */
    private PracticalSelectResponse analyzeWithTimeout(String keyword) {
        CompletableFuture<PracticalSelectResponse> future =
                CompletableFuture.supplyAsync(() -> service.analyze(keyword), ANALYZE_EXECUTOR);
        try {
            return future.get(ANALYZE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            log.warn("实战选股超时 [{}]s keyword={}", ANALYZE_TIMEOUT_SECONDS, keyword);
            return PracticalSelectResponse.builder()
                    .matched(false)
                    .message("分析超时（" + ANALYZE_TIMEOUT_SECONDS + "s），可能是上游行情接口/数据库连接繁忙，30 秒后重试或换一个关键词试试")
                    .build();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return PracticalSelectResponse.builder()
                    .matched(false)
                    .message("请求被中断，请重试")
                    .build();
        } catch (ExecutionException ee) {
            log.warn("实战选股异常 keyword={}: {}", keyword, ee.getCause() == null ? ee.getMessage() : ee.getCause().getMessage());
            Throwable cause = ee.getCause();
            String msg = cause == null ? "未知错误" : cause.getMessage();
            if (msg != null && msg.contains("Connection is not available")) {
                return PracticalSelectResponse.builder()
                        .matched(false)
                        .message("数据库连接池暂满（上游行情接口正在重试中），5 秒后重试")
                        .build();
            }
            return PracticalSelectResponse.builder()
                    .matched(false)
                    .message("分析失败：" + msg)
                    .build();
        }
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> r = new HashMap<>();
        r.put("ok", true);
        r.put("service", "practical-select");
        return r;
    }

    // ============ 历史记录 ============

    /**
     * 历史记录列表（分页）。kw 为空时返回全部。
     */
    @GetMapping("/records")
    public Map<String, Object> records(@RequestParam(value = "kw", required = false) String kw,
                                       @RequestParam(value = "page", required = false, defaultValue = "0") int page,
                                       @RequestParam(value = "size", required = false, defaultValue = "20") int size) {
        Page<InvestPracticalSelectRecord> p = service.listRecords(kw, page, size);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("total", p.getTotalElements());
        r.put("page", p.getNumber());
        r.put("size", p.getSize());
        // 列表只返回摘要，不含 resultJson
        r.put("records", p.getContent().stream().map(this::toSummary).toList());
        return r;
    }

    /**
     * 历史详情（返回完整 PracticalSelectResponse）。
     */
    @GetMapping("/record/{id}")
    public Map<String, Object> record(@PathVariable Long id) {
        PracticalSelectResponse data = service.getRecordResponse(id);
        InvestPracticalSelectRecord rec = recordRepository.findById(id).orElseThrow();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("id", rec.getId());
        r.put("stockCode", rec.getStockCode());
        r.put("stockName", rec.getStockName());
        r.put("keyword", rec.getKeyword());
        r.put("headline", rec.getHeadline());
        r.put("verdict", rec.getVerdict());
        r.put("status", rec.getStatus());
        r.put("pdfPath", rec.getPdfPath());
        r.put("shareEnabled", rec.getIsPublic() != null && rec.getIsPublic() == 1);
        r.put("shareToken", rec.getShareToken());
        r.put("elapsedMs", rec.getElapsedMs());
        r.put("createdAt", rec.getCreatedAt());
        r.put("data", data);
        return r;
    }

    /**
     * 删除记录。
     */
    @DeleteMapping("/record/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> delete(@PathVariable Long id) {
        service.deleteRecord(id);
        Map<String, Object> r = new HashMap<>();
        r.put("ok", true);
        r.put("id", id);
        r.put("message", "已删除");
        return r;
    }

    // ============ 分享 ============

    /**
     * 启用分享，返回完整分享 URL。
     */
    @PostMapping("/record/{id}/share")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> enableShare(@PathVariable Long id, HttpServletRequest req) {
        String baseUrl = req.getScheme() + "://" + req.getServerName()
                + (req.getServerPort() == 80 || req.getServerPort() == 443 ? "" : ":" + req.getServerPort());
        String url = service.enableShare(id, baseUrl);
        Map<String, Object> r = new HashMap<>();
        r.put("ok", true);
        r.put("id", id);
        r.put("shareUrl", url);
        return r;
    }

    /**
     * 关闭分享。
     */
    @DeleteMapping("/record/{id}/share")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> disableShare(@PathVariable Long id) {
        service.disableShare(id);
        Map<String, Object> r = new HashMap<>();
        r.put("ok", true);
        r.put("id", id);
        r.put("message", "已关闭分享");
        return r;
    }

    /**
     * 公开分享访问（无需鉴权）。
     */
    @GetMapping("/shared")
    public Map<String, Object> shared(@RequestParam("token") String token) {
        PracticalSelectResponse data = service.getShared(token);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("data", data);
        return r;
    }

    // ============ PDF ============

    /**
     * 生成并下载 PDF（懒生成 + 缓存路径）。
     */
    @GetMapping(value = "/record/{id}/pdf")
    public void pdf(@PathVariable Long id, HttpServletResponse response) throws java.io.IOException {
        InvestPracticalSelectRecord rec = recordRepository.findById(id).orElse(null);
        if (rec == null) {
            response.setStatus(404);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"ok\":false,\"code\":404,\"message\":\"记录不存在\"}");
            return;
        }
        try {
            String relPath = pdfService.generate(rec);
            rec.setPdfPath(relPath);
            recordRepository.save(rec);

            File file = pdfService.resolvePdfFile(relPath);
            if (file == null || !file.exists()) {
                response.setStatus(500);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"ok\":false,\"code\":500,\"message\":\"PDF 文件丢失\"}");
                return;
            }
            byte[] bytes = Files.readAllBytes(file.toPath());
            String displayName = (rec.getStockName() == null ? rec.getStockCode() : rec.getStockName())
                    + "-" + rec.getStockCode() + "-实战选股.pdf";
            String encoded = URLEncoder.encode(displayName, StandardCharsets.UTF_8).replace("+", "%20");
            String asciiFallback = rec.getStockCode() + "-practical-select.pdf";
            response.setStatus(200);
            response.setContentType("application/pdf;charset=UTF-8");
            response.setContentLength(bytes.length);
            response.setHeader("Content-Disposition",
                    "attachment; filename=\"" + asciiFallback + "\"; filename*=UTF-8''" + encoded);
            response.getOutputStream().write(bytes);
            response.getOutputStream().flush();
        } catch (Exception e) {
            log.error("PDF 导出失败: id={}", id, e);
            response.setStatus(500);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"ok\":false,\"code\":500,\"message\":\"PDF 生成失败: "
                    + e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    // ============ 工具 ============

    private Map<String, Object> toSummary(InvestPracticalSelectRecord rec) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rec.getId());
        m.put("stockCode", rec.getStockCode());
        m.put("stockName", rec.getStockName());
        m.put("keyword", rec.getKeyword());
        m.put("status", rec.getStatus());
        m.put("headline", rec.getHeadline());
        m.put("verdict", rec.getVerdict());
        m.put("pdfPath", rec.getPdfPath());
        m.put("shareEnabled", rec.getIsPublic() != null && rec.getIsPublic() == 1);
        m.put("shareToken", rec.getShareToken());
        m.put("elapsedMs", rec.getElapsedMs());
        m.put("createdAt", rec.getCreatedAt());
        return m;
    }
}
