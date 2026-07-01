package com.quant.service;

import com.quant.dto.invest.WeeklyOpportunitySlotDTO;
import com.quant.dto.invest.WeeklyOpportunityUpdateRequest;
import com.quant.entity.InvestStockPool;
import com.quant.entity.InvestWeeklyOpportunitySlot;
import com.quant.repository.InvestStockPoolRepository;
import com.quant.repository.InvestWeeklyOpportunitySlotRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 每周机会点（3×3 卡片）服务。
 *
 * 不变量：
 * 1. 每个 pool_type 固定返回 9 个 slot（不足补空）
 * 2. slot 顺序按 slotIndex 升序
 * 3. update() 是「全量替换」语义（事务内先删后插）
 * 4. stockName 实时联动 invest_stock_pool 补全（股票不在池中时为 null）
 * 5. imageUrl 是该格参考截图（admin 手工上传），与 stockCode 无强绑定——stockCode 留空时仍可单独存图
 *
 * 注意：估值水平 (level) 不由本服务计算，前端从 /api/invest/pool 拿快照后用 inferValuationRange() 算。
 */
@Slf4j
@Service
public class InvestWeeklyOpportunityService {

    public static final List<String> ALLOWED_POOL_TYPES = List.of("tech_vc", "innovative_drug", "quality");
    public static final int SLOTS_PER_POOL = 9;

    private static final Set<String> ALLOWED_IMAGE_EXT =
            Set.of("jpg", "jpeg", "png", "webp", "gif", "svg");

    private final InvestWeeklyOpportunitySlotRepository slotRepo;
    private final InvestStockPoolRepository stockPoolRepo;

    @Value("${app.upload-dir:uploads}")
    private String uploadDir;

    public InvestWeeklyOpportunityService(InvestWeeklyOpportunitySlotRepository slotRepo,
                                          InvestStockPoolRepository stockPoolRepo) {
        this.slotRepo = slotRepo;
        this.stockPoolRepo = stockPoolRepo;
    }

    // ══════════════════════════════════════════════════
    // 读取
    // ══════════════════════════════════════════════════

    @Cacheable(value = "weeklyOpportunity", key = "#poolType")
    @Transactional(readOnly = true)
    public List<WeeklyOpportunitySlotDTO> get(String poolType) {
        validatePoolType(poolType);
        Map<String, InvestStockPool> poolMap = loadStockMap(poolType);
        return buildSlots(poolType, slotRepo.findByPoolTypeOrderBySlotIndexAsc(poolType), poolMap);
    }

    @Cacheable(value = "weeklyOpportunity", key = "'all'")
    @Transactional(readOnly = true)
    public List<WeeklyOpportunitySlotDTO> listAll() {
        List<WeeklyOpportunitySlotDTO> all = new ArrayList<>();
        for (String type : ALLOWED_POOL_TYPES) {
            all.addAll(get(type));
        }
        return all;
    }

    // ══════════════════════════════════════════════════
    // 写
    // ══════════════════════════════════════════════════

    @CacheEvict(value = "weeklyOpportunity", allEntries = true)
    @Transactional
    public List<WeeklyOpportunitySlotDTO> update(String poolType, WeeklyOpportunityUpdateRequest req) {
        validatePoolType(poolType);
        validateSlots(req);

        slotRepo.deleteByPoolType(poolType);
        slotRepo.flush();

        List<InvestWeeklyOpportunitySlot> rows = new ArrayList<>();
        for (WeeklyOpportunityUpdateRequest.SlotItem item : req.getSlots()) {
            InvestWeeklyOpportunitySlot s = new InvestWeeklyOpportunitySlot();
            s.setPoolType(poolType);
            s.setSlotIndex(item.getSlotIndex());
            s.setStockCode(blankToNull(item.getStockCode()));
            s.setUserStockName(blankToNull(item.getUserStockName()));
            s.setReason(blankToNull(item.getReason()));
            // imageUrl 字段缺失（== null）则保留 DB 原值；显式传了空串视作清空
            if (item.getImageUrl() != null) {
                s.setImageUrl(blankToNull(item.getImageUrl()));
            }
            rows.add(s);
        }
        slotRepo.saveAll(rows);
        slotRepo.flush();

        log.info("Weekly opportunity updated: poolType={}, slotCount={}", poolType, rows.size());

        Map<String, InvestStockPool> poolMap = loadStockMap(poolType);
        return buildSlots(poolType, rows, poolMap);
    }

    // ══════════════════════════════════════════════════
    // 单 slot 截图：上传 / 清除
    // ══════════════════════════════════════════════════

    @CacheEvict(value = "weeklyOpportunity", allEntries = true)
    @Transactional
    public String setSlotImage(String poolType, int slotIndex, MultipartFile file) throws IOException {
        validatePoolType(poolType);
        validateSlotIndex(slotIndex);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("截图文件不能为空");
        }
        String originalName = file.getOriginalFilename() == null ? "shot" : file.getOriginalFilename();
        String ext = extOf(originalName);
        if (!ALLOWED_IMAGE_EXT.contains(ext)) {
            throw new IllegalArgumentException("仅支持 JPG/PNG/WebP/GIF/SVG 格式，当前：" + ext);
        }

        Path dir = Paths.get(uploadDir, "weekly-opportunity", poolType, String.valueOf(slotIndex))
                .toAbsolutePath().normalize();
        Files.createDirectories(dir);
        String safeName = UUID.randomUUID() + "_" + originalName.replaceAll("[\\\\/:*?\"<>|]", "_");
        Path target = dir.resolve(safeName);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("slot 截图已保存: {}", target);

        String publicUrl = "/uploads/weekly-opportunity/" + poolType + "/" + slotIndex + "/" + safeName;
        InvestWeeklyOpportunitySlot row = slotRepo.findByPoolTypeAndSlotIndex(poolType, slotIndex)
                .orElseGet(() -> {
                    InvestWeeklyOpportunitySlot s = new InvestWeeklyOpportunitySlot();
                    s.setPoolType(poolType);
                    s.setSlotIndex(slotIndex);
                    return s;
                });
        row.setImageUrl(publicUrl);
        slotRepo.save(row);
        return publicUrl;
    }

    @CacheEvict(value = "weeklyOpportunity", allEntries = true)
    @Transactional
    public void clearSlotImage(String poolType, int slotIndex) {
        validatePoolType(poolType);
        validateSlotIndex(slotIndex);
        slotRepo.findByPoolTypeAndSlotIndex(poolType, slotIndex).ifPresent(row -> {
            if (row.getImageUrl() != null) {
                row.setImageUrl(null);
                slotRepo.save(row);
                log.info("slot 截图已清除: poolType={}, slotIndex={}", poolType, slotIndex);
            }
        });
    }

    // ══════════════════════════════════════════════════
    // 内部
    // ══════════════════════════════════════════════════

    private Map<String, InvestStockPool> loadStockMap(String poolType) {
        Map<String, InvestStockPool> map = new HashMap<>();
        for (InvestStockPool p : stockPoolRepo.findByPoolTypeOrderByCreatedAtDesc(poolType)) {
            map.put(p.getStockCode(), p);
        }
        return map;
    }

    /**
     * 把持久化行 + 联动数据 → 9 个 DTO。空 slot 也保留（stockCode=null）。
     */
    private List<WeeklyOpportunitySlotDTO> buildSlots(String poolType,
                                                      List<InvestWeeklyOpportunitySlot> rows,
                                                      Map<String, InvestStockPool> poolMap) {
        Map<Integer, InvestWeeklyOpportunitySlot> byIndex = new HashMap<>();
        for (InvestWeeklyOpportunitySlot s : rows) {
            byIndex.put(s.getSlotIndex(), s);
        }

        List<WeeklyOpportunitySlotDTO> out = new ArrayList<>(SLOTS_PER_POOL);
        for (int i = 0; i < SLOTS_PER_POOL; i++) {
            InvestWeeklyOpportunitySlot s = byIndex.get(i);
            if (s == null || s.getStockCode() == null) {
                out.add(WeeklyOpportunitySlotDTO.builder()
                        .poolType(poolType)
                        .slotIndex(i)
                        .userStockName(s == null ? null : s.getUserStockName())
                        .imageUrl(s == null ? null : s.getImageUrl())
                        .build());
            } else {
                InvestStockPool stock = poolMap.get(s.getStockCode());
                out.add(WeeklyOpportunitySlotDTO.builder()
                        .poolType(poolType)
                        .slotIndex(i)
                        .stockCode(s.getStockCode())
                        .stockName(stock != null ? stock.getStockName() : null)
                        .userStockName(s.getUserStockName())
                        .reason(s.getReason())
                        .imageUrl(s.getImageUrl())
                        .updatedAt(s.getUpdatedAt())
                        .build());
            }
        }
        return out;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    private static final Set<String> ALLOWED_SET = new LinkedHashSet<>(ALLOWED_POOL_TYPES);

    private static void validatePoolType(String poolType) {
        if (poolType == null || poolType.isBlank()) {
            throw new IllegalArgumentException("poolType 不能为空");
        }
        if (!ALLOWED_SET.contains(poolType)) {
            throw new IllegalArgumentException("不支持的 poolType：" + poolType + "（允许：tech_vc / innovative_drug / quality）");
        }
    }

    private static void validateSlotIndex(Integer slotIndex) {
        if (slotIndex == null || slotIndex < 0 || slotIndex >= SLOTS_PER_POOL) {
            throw new IllegalArgumentException("slotIndex 越界（必须在 0~" + (SLOTS_PER_POOL - 1) + "），实际 " + slotIndex);
        }
    }

    private static void validateSlots(WeeklyOpportunityUpdateRequest req) {
        if (req == null || req.getSlots() == null) {
            throw new IllegalArgumentException("slots 不能为空");
        }
        if (req.getSlots().size() != SLOTS_PER_POOL) {
            throw new IllegalArgumentException("slots 数量必须等于 " + SLOTS_PER_POOL + "，实际 " + req.getSlots().size());
        }
        Set<Integer> seen = new HashSet<>();
        for (WeeklyOpportunityUpdateRequest.SlotItem item : req.getSlots()) {
            if (item.getSlotIndex() == null || item.getSlotIndex() < 0 || item.getSlotIndex() >= SLOTS_PER_POOL) {
                throw new IllegalArgumentException("slotIndex 越界（必须在 0~" + (SLOTS_PER_POOL - 1) + "），实际 " + item.getSlotIndex());
            }
            if (!seen.add(item.getSlotIndex())) {
                throw new IllegalArgumentException("slotIndex 重复：" + item.getSlotIndex());
            }
        }
    }
}
