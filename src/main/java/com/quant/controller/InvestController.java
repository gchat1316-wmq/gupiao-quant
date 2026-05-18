package com.quant.controller;

import com.quant.dto.invest.PoolItemDTO;
import com.quant.dto.invest.PoolSaveRequest;
import com.quant.dto.invest.ProsperityResultDTO;
import com.quant.service.InvestService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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

    public InvestController(InvestService investService) {
        this.investService = investService;
    }

    /** 景气度扫描 + 16季度热力表（同一接口，quarters 参数控制深度） */
    @GetMapping("/prosperity")
    public ProsperityResultDTO queryProsperity(
            @RequestParam("keywords") String keywords,
            @RequestParam(value = "quarters", required = false) Integer quarters) {
        return investService.queryProsperity(keywords, quarters);
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

    /** 更新股票池条目 */
    @PutMapping("/pool/{id}")
    public PoolItemDTO updatePool(@PathVariable Integer id, @RequestBody PoolSaveRequest req) {
        return investService.updatePool(id, req);
    }

    /** 移除股票池条目 */
    @DeleteMapping("/pool/{id}")
    public ResponseEntity<Map<String, String>> removeFromPool(@PathVariable Integer id) {
        investService.removeFromPool(id);
        return ResponseEntity.ok(Map.of("message", "已移除"));
    }
}
