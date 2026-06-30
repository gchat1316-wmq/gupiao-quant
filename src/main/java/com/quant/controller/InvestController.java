package com.quant.controller;

import com.quant.dto.invest.BatchImportRequest;
import com.quant.dto.invest.BatchImportResultDTO;
import com.quant.dto.invest.OcrImportRequest;
import com.quant.dto.invest.OcrParseResultDTO;
import com.quant.dto.invest.PoolFieldUpdateRequest;
import com.quant.dto.invest.PoolItemDTO;
import com.quant.dto.invest.PoolMetaDTO;
import com.quant.dto.invest.PoolMetaUpdateRequest;
import com.quant.dto.invest.PoolSaveRequest;
import com.quant.dto.invest.SopCheckupDTO;
import com.quant.dto.invest.WeeklyOpportunitySlotDTO;
import com.quant.dto.invest.WeeklyOpportunityUpdateRequest;
import com.quant.service.InvestPoolMetaService;
import com.quant.service.InvestService;
import com.quant.service.InvestPoolRefreshService;
import com.quant.service.InvestPoolSeedService;
import com.quant.service.InvestWeeklyOpportunityService;
import com.quant.service.OcrPoolImportService;
import com.quant.service.PriceMonitorService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/invest")
@CrossOrigin(origins = "*")
public class InvestController {

    private final InvestService investService;
    private final OcrPoolImportService ocrService;
    private final PriceMonitorService priceMonitorService;
    private final InvestPoolSeedService poolSeedService;
    private final InvestPoolRefreshService poolRefreshService;
    private final InvestPoolMetaService poolMetaService;
    private final InvestWeeklyOpportunityService weeklyOpportunityService;

    public InvestController(InvestService investService,
                            OcrPoolImportService ocrService,
                            PriceMonitorService priceMonitorService,
                            InvestPoolSeedService poolSeedService,
                            InvestPoolRefreshService poolRefreshService,
                            InvestPoolMetaService poolMetaService,
                            InvestWeeklyOpportunityService weeklyOpportunityService) {
        this.investService = investService;
        this.ocrService = ocrService;
        this.priceMonitorService = priceMonitorService;
        this.poolSeedService = poolSeedService;
        this.poolRefreshService = poolRefreshService;
        this.poolMetaService = poolMetaService;
        this.weeklyOpportunityService = weeklyOpportunityService;
    }

    /** 实战选股 SOP · 三大数字体检 */
    @GetMapping("/sop/checkup")
    public SopCheckupDTO sopCheckup(@RequestParam("keyword") String keyword) {
        return investService.sopCheckup(keyword);
    }

    /** 获取股票池列表（可选按 poolType 过滤） */
    @GetMapping("/pool")
    public List<PoolItemDTO> listPool(@RequestParam(value = "poolType", required = false) String poolType) {
        if (poolType == null || poolType.isBlank()) {
            return investService.listPool();
        }
        return investService.listPool().stream()
                .filter(p -> poolType.equals(p.getPoolType()))
                .toList();
    }

    // ===== 股票池元信息（估值方法、周度机会、封面图） =====

    /** 获取全部股票池元信息（公开） */
    @GetMapping("/pool-meta")
    public List<PoolMetaDTO> listPoolMeta() {
        return poolMetaService.listAll();
    }

    /** 获取单个股票池元信息（公开） */
    @GetMapping("/pool-meta/{poolType}")
    public ResponseEntity<PoolMetaDTO> getPoolMeta(@PathVariable String poolType) {
        PoolMetaDTO dto = poolMetaService.get(poolType);
        if (dto == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(dto);
    }

    /** 更新股票池元信息（MANAGER + ADMIN） */
    @PutMapping("/pool-meta/{poolType}")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public PoolMetaDTO updatePoolMeta(@PathVariable String poolType,
                                      @RequestBody PoolMetaUpdateRequest req) {
        return poolMetaService.update(poolType, req);
    }

    /** 上传股票池封面图（MANAGER + ADMIN） */
    @PostMapping(value = "/pool-meta/{poolType}/cover-image", consumes = "multipart/form-data")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public Map<String, String> uploadPoolMetaCover(@PathVariable String poolType,
                                                   @RequestPart("file") MultipartFile file) throws IOException {
        return poolMetaService.setCoverImage(poolType, file);
    }

    /** 加入股票池 */
    @PostMapping("/pool")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public PoolItemDTO addToPool(@RequestBody PoolSaveRequest req) {
        return investService.addToPool(req);
    }

    /** 更新股票池条目（整体更新） */
    @PutMapping("/pool/{id}")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public PoolItemDTO updatePool(@PathVariable Integer id, @RequestBody PoolSaveRequest req) {
        return investService.updatePool(id, req);
    }

    /** 内联编辑：单字段更新 */
    @PatchMapping("/pool/{id}/field")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public PoolItemDTO updatePoolField(@PathVariable Integer id,
                                       @RequestBody PoolFieldUpdateRequest req) {
        return investService.updateField(id, req);
    }

    /** 移除股票池条目 */
    @DeleteMapping("/pool/{id}")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public ResponseEntity<Map<String, String>> removeFromPool(@PathVariable Integer id) {
        investService.removeFromPool(id);
        return ResponseEntity.ok(Map.of("message", "已移除"));
    }

    /** 截图批量导入：仅解析图片，返回识别结果（不入库），由前端预览后再调用 batch-import */
    @PostMapping("/pool/import-image")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public OcrParseResultDTO importFromImage(@RequestBody OcrImportRequest req) {
        return ocrService.parseImage(req);
    }

    /** 截图批量导入：将前端确认后的列表批量入库 */
    @PostMapping("/pool/batch-import")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public BatchImportResultDTO batchImport(@RequestBody BatchImportRequest req) {
        return ocrService.batchImport(req);
    }

    /** 手动触发价格监控（调试用）。 */
    @PostMapping("/pool/monitor/run")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public Map<String, String> runPriceMonitor() {
        priceMonitorService.monitorPrices();
        return Map.of("message", "monitor triggered");
    }

    /** 按截图顺序重建科技风投股票池。 */
    @PostMapping("/pool/seed/tech-vc-screenshot")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public Map<String, Object> seedTechVcScreenshotPool() {
        int inserted = poolSeedService.replaceTechVcWithScreenshotPool();
        return Map.of("message", "tech_vc pool rebuilt", "inserted", inserted);
    }

    /** 手动触发股票池周末刷新逻辑。补齐所有池类型（quality + tech_vc）的缺失字段。 */
    @PostMapping("/pool/refresh")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public Map<String, Object> refreshPool() {
        int refreshed = poolRefreshService.refreshAllPoolSnapshots();
        return Map.of("message", "pool refreshed", "refreshed", refreshed);
    }

    // ===== 每周机会点（3×3 卡片） =====

    /** 读取所有 3 个分类的 27 个 slot（公开） */
    @GetMapping("/weekly-opportunity")
    public List<WeeklyOpportunitySlotDTO> listAllWeeklyOpportunity() {
        return weeklyOpportunityService.listAll();
    }

    /** 读取单个分类的 9 个 slot（公开，slotIndex 0~8） */
    @GetMapping("/weekly-opportunity/{poolType}")
    public List<WeeklyOpportunitySlotDTO> getWeeklyOpportunity(@PathVariable String poolType) {
        return weeklyOpportunityService.get(poolType);
    }

    /** 全量替换某个分类的 9 个 slot（MANAGER + ADMIN） */
    @PutMapping("/weekly-opportunity/{poolType}")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public List<WeeklyOpportunitySlotDTO> updateWeeklyOpportunity(@PathVariable String poolType,
                                                                   @RequestBody WeeklyOpportunityUpdateRequest req) {
        return weeklyOpportunityService.update(poolType, req);
    }
}
