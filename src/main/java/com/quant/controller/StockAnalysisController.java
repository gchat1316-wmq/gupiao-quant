package com.quant.controller;

import com.quant.dto.stockanalysis.StockAnalysisRecordListDTO;
import com.quant.dto.stockanalysis.StockAnalysisRequest;
import com.quant.dto.stockanalysis.StockAnalysisResponse;
import com.quant.entity.StockAnalysisRecord;
import com.quant.service.StockAnalysisPdfService;
import com.quant.service.StockAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/stock-analysis")
@RequiredArgsConstructor
public class StockAnalysisController {

    private final StockAnalysisService service;
    private final StockAnalysisPdfService pdfService;

    /**
    * 提交分析任务 (立即返回 recordId)
     */
    @PostMapping("/submit")
    public Map<String, Object> submit(@RequestBody StockAnalysisRequest req) {
        try {
            Long id = service.submit(req);
            // 异步执行 (Spring 任务池)
            service.executeAsync(id);
            Map<String, Object> r = new HashMap<>();
            r.put("ok", true);
            r.put("recordId", id);
            r.put("status", "PENDING");
            r.put("message", "已提交, 预计 30-90 秒完成, 请通过 /status/{id} 查询进度");
            return r;
        } catch (IllegalArgumentException e) {
            return error(400, e.getMessage());
        } catch (Exception e) {
            log.error("提交失败", e);
            return error(500, "提交失败: " + e.getMessage());
        }
    }

    /**
     * 查询状态
     */
    @GetMapping("/status/{id}")
    public Map<String, Object> status(@PathVariable Long id) {
        StockAnalysisRecord rec = service.getById(id);
        if (rec == null) {
            return error(404, "记录不存在");
        }
        Map<String, Object> r = new HashMap<>();
        r.put("ok", true);
        r.put("id", rec.getId());
        r.put("stockCode", rec.getStockCode());
        r.put("stockName", rec.getStockName());
        r.put("status", rec.getStatus());
        r.put("verdict", rec.getVerdict());
        r.put("moatScore", rec.getMoatScore());
        r.put("currentPrice", rec.getCurrentPrice());
        r.put("elapsedMs", rec.getElapsedMs());
        r.put("errorMessage", rec.getErrorMessage());
        r.put("submittedAt", rec.getSubmittedAt());
        r.put("startedAt", rec.getStartedAt());
        r.put("finishedAt", rec.getFinishedAt());
        // 如果还在 RUNNING 且 elapsedMs 缺失, 给个当前已用时间
        if ("RUNNING".equals(rec.getStatus()) && rec.getStartedAt() != null) {
            long runningMs = java.time.Duration.between(rec.getStartedAt(), java.time.LocalDateTime.now()).toMillis();
            r.put("runningMs", runningMs);
        }
        return r;
    }

    /**
     * 拉取完整研报 JSON
     */
    @GetMapping("/record/{id}")
    public Map<String, Object> record(@PathVariable Long id) {
        StockAnalysisRecord rec = service.getById(id);
        if (rec == null) {
            return error(404, "记录不存在");
        }
        StockAnalysisResponse resp = service.parseRecordJson(rec);
        Map<String, Object> r = new HashMap<>();
        r.put("ok", true);
        r.put("id", rec.getId());
        r.put("status", rec.getStatus());
        r.put("stockCode", rec.getStockCode());
        r.put("stockName", rec.getStockName());
        r.put("method", rec.getMethod());
        r.put("submittedAt", rec.getSubmittedAt());
        r.put("finishedAt", rec.getFinishedAt());
        r.put("elapsedMs", rec.getElapsedMs());
        r.put("report", resp);
        return r;
    }

    /**
     * 列表 (分页)
     */
    @GetMapping("/list")
    public Map<String, Object> list(
            @RequestParam(value = "kw", required = false, defaultValue = "") String kw,
            @RequestParam(value = "status", required = false, defaultValue = "") String status,
            @RequestParam(value = "page", required = false, defaultValue = "0") int page,
            @RequestParam(value = "size", required = false, defaultValue = "20") int size) {
        Page<StockAnalysisRecordListDTO> p = service.list(kw, status, page, size);
        Map<String, Object> r = new HashMap<>();
        r.put("ok", true);
        r.put("total", p.getTotalElements());
        r.put("page", p.getNumber());
        r.put("size", p.getSize());
        r.put("records", p.getContent());
        return r;
    }

    /**
     * 懒生成 + 返回 PDF (一次性 stream 下载)
     * - 首次调用生成, 之后复用
     * - SUCCESS 状态才允许
     */
    @GetMapping(value = "/pdf/{id}")
    public void pdf(@PathVariable Long id, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        StockAnalysisRecord rec = service.getById(id);
        if (rec == null) {
            response.setStatus(404);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"ok\":false,\"code\":404,\"message\":\"记录不存在\"}");
            return;
        }
        if (!"SUCCESS".equals(rec.getStatus())) {
            response.setStatus(400);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"ok\":false,\"code\":400,\"message\":\"仅 SUCCESS 状态可导出, 当前: " + rec.getStatus() + "\"}");
            return;
        }
        try {
            String relativePath = pdfService.generate(rec);
            rec.setPdfPath(relativePath);
            service.save(rec);

            java.io.File file = pdfService.resolvePdfFile(relativePath);
            if (file == null || !file.exists()) {
                response.setStatus(500);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"ok\":false,\"code\":500,\"message\":\"PDF 文件丢失\"}");
                return;
            }
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            // HTTP header 不允许非 ASCII, 全部 URL 编码
            String displayName = (rec.getStockName() == null ? rec.getStockCodeRaw() : rec.getStockName())
                    + "-" + rec.getStockCodeRaw() + "-分析报告.pdf";
            String encoded = java.net.URLEncoder.encode(displayName, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20");
            response.setStatus(200);
            response.setContentType("application/pdf;charset=UTF-8");
            response.setContentLength(bytes.length);
            // 使用 RFC 5987 双 filename, ASCII fallback 用 stockCode (e.g. 688627-analysis.pdf)
            String asciiFallback = rec.getStockCodeRaw() + "-analysis.pdf";
            response.setHeader("Content-Disposition",
                    "attachment; filename=\"" + asciiFallback + "\"; filename*=UTF-8''" + encoded);
            response.getOutputStream().write(bytes);
            response.getOutputStream().flush();
        } catch (Exception e) {
            log.error("PDF 导出失败: id={}", id, e);
            response.setStatus(500);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"ok\":false,\"code\":500,\"message\":\"PDF 生成失败: " + e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    /**
     * 删除单条分析记录 (仅允许删除 FAILED 状态, 同时清理生成的 PDF 文件)
     */
    @DeleteMapping("/record/{id}")
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> deleteRecord(@PathVariable Long id) {
        StockAnalysisRecord rec = service.getById(id);
        if (rec == null) {
            return error(404, "记录不存在");
        }
        if (!"FAILED".equals(rec.getStatus())) {
            return error(400, "仅允许删除 FAILED 状态的记录, 当前: " + rec.getStatus());
        }
        // 尝试删除关联的 PDF 文件 (失败不阻塞主流程)
        if (rec.getPdfPath() != null && !rec.getPdfPath().isBlank()) {
            try {
                java.io.File pdfFile = pdfService.resolvePdfFile(rec.getPdfPath());
                if (pdfFile != null && pdfFile.exists() && pdfFile.delete()) {
                    log.info("随记录删除 PDF: id={} path={}", id, rec.getPdfPath());
                }
            } catch (Exception e) {
                log.warn("删除 PDF 文件失败 (忽略): id={} path={}", id, rec.getPdfPath(), e);
            }
        }
        service.deleteById(id);
        Map<String, Object> r = new HashMap<>();
        r.put("ok", true);
        r.put("id", id);
        r.put("message", "已删除");
        return r;
    }

    /**
     * 健康检查 (无需鉴权)
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> r = new HashMap<>();
        r.put("ok", true);
        r.put("status", "running");
        r.put("service", "stock-analysis");
        r.put("dataSource", "baostock");
        return r;
    }

    private Map<String, Object> error(int code, String message) {
        Map<String, Object> r = new HashMap<>();
        r.put("ok", false);
        r.put("code", code);
        r.put("message", message);
        return r;
    }
}
