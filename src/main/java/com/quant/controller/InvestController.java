package com.quant.controller;

import com.quant.dto.invest.BatchImportRequest;
import com.quant.dto.invest.BatchImportResultDTO;
import com.quant.dto.invest.OcrImportRequest;
import com.quant.dto.invest.OcrParseResultDTO;
import com.quant.dto.invest.PoolFieldUpdateRequest;
import com.quant.dto.invest.PoolItemDTO;
import com.quant.dto.invest.PoolSaveRequest;
import com.quant.dto.invest.ProsperityResultDTO;
import com.quant.dto.invest.SopCheckupDTO;
import com.quant.service.InvestService;
import com.quant.service.OcrPoolImportService;
import com.quant.service.PriceMonitorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/invest")
@CrossOrigin(origins = "*")
public class InvestController {

    private final InvestService investService;
    private final OcrPoolImportService ocrService;
    private final PriceMonitorService priceMonitorService;

    public InvestController(InvestService investService,
                            OcrPoolImportService ocrService,
                            PriceMonitorService priceMonitorService) {
        this.investService = investService;
        this.ocrService = ocrService;
        this.priceMonitorService = priceMonitorService;
    }

    /** 景气度扫描 + 16季度热力表（同一接口，quarters 参数控制深度） */
    @GetMapping("/prosperity")
    public ProsperityResultDTO queryProsperity(
            @RequestParam("keywords") String keywords,
            @RequestParam(value = "quarters", required = false) Integer quarters) {
        return investService.queryProsperity(keywords, quarters);
    }

    /** 实战选股 SOP · 三大数字体检 */
    @GetMapping("/sop/checkup")
    public SopCheckupDTO sopCheckup(@RequestParam("keyword") String keyword) {
        return investService.sopCheckup(keyword);
    }

    /** 获取股票池列表 */
    @GetMapping("/pool")
    public List<PoolItemDTO> listPool() {
        return investService.listPool();
    }

    /** 加入股票池 */
    @PostMapping("/pool")
    public PoolItemDTO addToPool(@RequestBody PoolSaveRequest req) {
        return investService.addToPool(req);
    }

    /** 更新股票池条目（整体更新） */
    @PutMapping("/pool/{id}")
    public PoolItemDTO updatePool(@PathVariable Integer id, @RequestBody PoolSaveRequest req) {
        return investService.updatePool(id, req);
    }

    /** 内联编辑：单字段更新 */
    @PatchMapping("/pool/{id}/field")
    public PoolItemDTO updatePoolField(@PathVariable Integer id,
                                       @RequestBody PoolFieldUpdateRequest req) {
        return investService.updateField(id, req);
    }

    /** 移除股票池条目 */
    @DeleteMapping("/pool/{id}")
    public ResponseEntity<Map<String, String>> removeFromPool(@PathVariable Integer id) {
        investService.removeFromPool(id);
        return ResponseEntity.ok(Map.of("message", "已移除"));
    }

    /** 截图批量导入：仅解析图片，返回识别结果（不入库），由前端预览后再调用 batch-import */
    @PostMapping("/pool/import-image")
    public OcrParseResultDTO importFromImage(@RequestBody OcrImportRequest req) {
        return ocrService.parseImage(req);
    }

    /** 截图批量导入：将前端确认后的列表批量入库 */
    @PostMapping("/pool/batch-import")
    public BatchImportResultDTO batchImport(@RequestBody BatchImportRequest req) {
        return ocrService.batchImport(req);
    }

    /** 手动触发价格监控（调试用）。 */
    @PostMapping("/pool/monitor/run")
    public Map<String, String> runPriceMonitor() {
        priceMonitorService.monitorPrices();
        return Map.of("message", "monitor triggered");
    }
}
