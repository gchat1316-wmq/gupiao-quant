package com.quant.invest;

import com.quant.dto.invest.WeeklyOpportunitySlotDTO;
import com.quant.dto.invest.WeeklyOpportunityUpdateRequest;
import com.quant.entity.InvestStockPool;
import com.quant.entity.InvestWeeklyOpportunitySlot;
import com.quant.repository.InvestStockPoolRepository;
import com.quant.repository.InvestWeeklyOpportunitySlotRepository;
import com.quant.service.InvestWeeklyOpportunityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 每周机会点 (3×3 卡片) 服务单测。
 *
 * 关键不变量：
 * 1. 每次返回的 slot 数量恒为 9（不足 9 个时用空 slot 补齐）
 * 2. slot 顺序按 slotIndex 升序
 * 3. stockName 从 invest_stock_pool 联动 — 股票已不在股票池时为 null
 * 4. update() 是「全量替换」语义：先删旧 9 行，再插新 9 行（事务内）
 * 5. update() 拒绝 poolType ∉ {tech_vc, innovative_drug, quality}
 * 6. update() 拒绝 slotIndex ∉ [0, 8]
 * 7. update() 拒绝 slotIndex 重复
 * 8. level 字段不由 service 计算（前端用 inferValuationRange 自算）
 */
@DisplayName("InvestWeeklyOpportunityService")
class InvestWeeklyOpportunityServiceTest {

    private static final int SLOTS_PER_POOL = 9;

    private final InvestWeeklyOpportunitySlotRepository repo =
            mock(InvestWeeklyOpportunitySlotRepository.class);
    private final InvestStockPoolRepository stockPoolRepo =
            mock(InvestStockPoolRepository.class);

    private final InvestWeeklyOpportunityService service =
            new InvestWeeklyOpportunityService(repo, stockPoolRepo);

    // ══════════════════════════════════════════════════
    // get(poolType)
    // ══════════════════════════════════════════════════

    @Test
    @DisplayName("get：表为空时返回 9 个空 slot（按 0~8 顺序）")
    void getReturnsNineEmptySlotsWhenTableEmpty() {
        when(repo.findByPoolTypeOrderBySlotIndexAsc("tech_vc")).thenReturn(List.of());
        when(stockPoolRepo.findByPoolTypeOrderByCreatedAtDesc("tech_vc")).thenReturn(List.of());

        List<WeeklyOpportunitySlotDTO> result = service.get("tech_vc");

        assertThat(result).hasSize(SLOTS_PER_POOL);
        for (int i = 0; i < SLOTS_PER_POOL; i++) {
            WeeklyOpportunitySlotDTO s = result.get(i);
            assertThat(s.getSlotIndex()).isEqualTo(i);
            assertThat(s.getStockCode()).isNull();
            assertThat(s.getStockName()).isNull();
            assertThat(s.getReason()).isNull();
            assertThat(s.getUpdatedAt()).isNull();
        }
    }

    @Test
    @DisplayName("get：少于 9 行时补齐到 9 行")
    void getPadsMissingSlots() {
        when(repo.findByPoolTypeOrderBySlotIndexAsc("innovative_drug")).thenReturn(List.of(
                slot(0, "300760", "回踩到位"),
                slot(2, "600276", "GLP-1 催化")
        ));
        when(stockPoolRepo.findByPoolTypeOrderByCreatedAtDesc("innovative_drug")).thenReturn(List.of(
                stock("300760", "迈瑞医疗"),
                stock("600276", "恒瑞医药")
        ));

        List<WeeklyOpportunitySlotDTO> result = service.get("innovative_drug");

        assertThat(result).hasSize(9);
        assertThat(result.get(0).getStockCode()).isEqualTo("300760");
        assertThat(result.get(0).getStockName()).isEqualTo("迈瑞医疗");
        assertThat(result.get(1).getStockCode()).isNull();
        assertThat(result.get(2).getStockCode()).isEqualTo("600276");
        assertThat(result.get(2).getStockName()).isEqualTo("恒瑞医药");
        assertThat(result.get(8).getStockCode()).isNull();
    }

    @Test
    @DisplayName("get：股票已从股票池移除时 stockName 联动为 null（不抛异常）")
    void getStockNameNullWhenStockNotInPool() {
        when(repo.findByPoolTypeOrderBySlotIndexAsc("quality")).thenReturn(List.of(
                slot(0, "000858", "PE 14×"),
                slot(1, "999999", "已退市")  // 不在股票池里
        ));
        when(stockPoolRepo.findByPoolTypeOrderByCreatedAtDesc("quality")).thenReturn(List.of(
                stock("000858", "五粮液")
                // 999999 不在列表
        ));

        List<WeeklyOpportunitySlotDTO> result = service.get("quality");

        assertThat(result).hasSize(9);
        assertThat(result.get(0).getStockName()).isEqualTo("五粮液");
        assertThat(result.get(1).getStockCode()).isEqualTo("999999");
        assertThat(result.get(1).getStockName()).isNull();
    }

    @Test
    @DisplayName("get：DTO 不带 level 字段（前端用 inferValuationRange 自算）")
    void getDtoHasNoLevelField() {
        when(repo.findByPoolTypeOrderBySlotIndexAsc("tech_vc")).thenReturn(List.of());
        when(stockPoolRepo.findByPoolTypeOrderByCreatedAtDesc("tech_vc")).thenReturn(List.of());

        List<WeeklyOpportunitySlotDTO> result = service.get("tech_vc");

        // DTO 不暴露 level
        assertThat(WeeklyOpportunitySlotDTO.class.getDeclaredFields())
                .extracting("name")
                .doesNotContain("level");
        // 结果集本身正常
        assertThat(result).hasSize(9);
    }

    // ══════════════════════════════════════════════════
    // listAll()
    // ══════════════════════════════════════════════════

    @Test
    @DisplayName("listAll：按固定顺序返回 3 个分类 × 9 = 27 行")
    void listAllReturnsFixedOrder() {
        for (String type : new String[]{"tech_vc", "innovative_drug", "quality"}) {
            when(repo.findByPoolTypeOrderBySlotIndexAsc(type)).thenReturn(List.of());
            when(stockPoolRepo.findByPoolTypeOrderByCreatedAtDesc(type)).thenReturn(List.of());
        }

        List<WeeklyOpportunitySlotDTO> result = service.listAll();

        assertThat(result).hasSize(27);
        assertThat(result.get(0).getPoolType()).isEqualTo("tech_vc");
        assertThat(result.get(0).getSlotIndex()).isEqualTo(0);
        assertThat(result.get(8).getPoolType()).isEqualTo("tech_vc");
        assertThat(result.get(8).getSlotIndex()).isEqualTo(8);
        assertThat(result.get(9).getPoolType()).isEqualTo("innovative_drug");
        assertThat(result.get(26).getPoolType()).isEqualTo("quality");
    }

    // ══════════════════════════════════════════════════
    // update() — 全量替换
    // ══════════════════════════════════════════════════

    @Test
    @DisplayName("update：合法输入 → 全量替换（先删后插，事务内）")
    void updateReplacesAllNineSlots() {
        when(stockPoolRepo.findByPoolTypeOrderByCreatedAtDesc("tech_vc")).thenReturn(List.of(
                stock("002371", "北方华创"),
                stock("300750", "宁德时代")
        ));

        WeeklyOpportunityUpdateRequest req = new WeeklyOpportunityUpdateRequest();
        req.setSlots(List.of(
                slotReq(0, "002371", "回踩 295"),
                slotReq(1, "300750", "PE 19×"),
                slotReq(2, null, null),
                slotReq(3, null, null),
                slotReq(4, null, null),
                slotReq(5, null, null),
                slotReq(6, null, null),
                slotReq(7, null, null),
                slotReq(8, null, null)
        ));

        List<WeeklyOpportunitySlotDTO> result = service.update("tech_vc", req);

        verify(repo).deleteByPoolType("tech_vc");
        ArgumentCaptor<List<InvestWeeklyOpportunitySlot>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(captor.capture());
        List<InvestWeeklyOpportunitySlot> saved = captor.getValue();
        assertThat(saved).hasSize(9);
        assertThat(saved.get(0).getSlotIndex()).isEqualTo(0);
        assertThat(saved.get(0).getStockCode()).isEqualTo("002371");
        assertThat(saved.get(0).getReason()).isEqualTo("回踩 295");
        assertThat(saved.get(0).getPoolType()).isEqualTo("tech_vc");
        assertThat(saved.get(1).getStockCode()).isEqualTo("300750");
        assertThat(saved.get(2).getStockCode()).isNull();
        assertThat(saved.get(2).getReason()).isNull();

        // 返回值带 stockName 联动
        assertThat(result).hasSize(9);
        assertThat(result.get(0).getStockName()).isEqualTo("北方华创");
        assertThat(result.get(1).getStockName()).isEqualTo("宁德时代");
        assertThat(result.get(2).getStockName()).isNull();
    }

    @Test
    @DisplayName("update：poolType 不合法 → 抛 IllegalArgumentException，不动库")
    void updateRejectsInvalidPoolType() {
        WeeklyOpportunityUpdateRequest req = new WeeklyOpportunityUpdateRequest();
        req.setSlots(nineEmpty());

        try {
            service.update("invalid_type", req);
            org.junit.jupiter.api.Assertions.fail("应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("invalid_type");
        }

        verify(repo, never()).deleteByPoolType(any());
        verify(repo, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("update：slots 数量不等于 9 → 抛 IllegalArgumentException")
    void updateRejectsWrongSlotCount() {
        WeeklyOpportunityUpdateRequest req = new WeeklyOpportunityUpdateRequest();
        req.setSlots(List.of(slotReq(0, "002371", "x")));

        try {
            service.update("tech_vc", req);
            org.junit.jupiter.api.Assertions.fail("应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("9");
        }

        verify(repo, never()).deleteByPoolType(any());
    }

    @Test
    @DisplayName("update：slotIndex 越界 [0,8] → 抛 IllegalArgumentException")
    void updateRejectsOutOfRangeSlotIndex() {
        WeeklyOpportunityUpdateRequest req = new WeeklyOpportunityUpdateRequest();
        List<WeeklyOpportunityUpdateRequest.SlotItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) items.add(slotReq(i, null, null));
        items.set(8, slotReq(9, null, null)); // 越界成 9
        req.setSlots(items);

        try {
            service.update("tech_vc", req);
            org.junit.jupiter.api.Assertions.fail("应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).containsAnyOf("slotIndex", "越界", "9");
        }

        verify(repo, never()).deleteByPoolType(any());
    }

    @Test
    @DisplayName("update：slotIndex 重复 → 抛 IllegalArgumentException")
    void updateRejectsDuplicateSlotIndex() {
        WeeklyOpportunityUpdateRequest req = new WeeklyOpportunityUpdateRequest();
        List<WeeklyOpportunityUpdateRequest.SlotItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) items.add(slotReq(i, null, null));
        items.set(1, slotReq(0, "002371", "dup")); // 重复成 0
        req.setSlots(items);

        try {
            service.update("tech_vc", req);
            org.junit.jupiter.api.Assertions.fail("应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).containsAnyOf("重复", "duplicate");
        }

        verify(repo, never()).deleteByPoolType(any());
    }

    @Test
    @DisplayName("update：空字符串 stockCode 视作 null（清空该格）")
    void updateBlankStockCodeTreatedAsNull() {
        when(stockPoolRepo.findByPoolTypeOrderByCreatedAtDesc("tech_vc")).thenReturn(List.of());

        WeeklyOpportunityUpdateRequest req = new WeeklyOpportunityUpdateRequest();
        req.setSlots(List.of(
                slotReq(0, "  ", null), // 空白字符串
                slotReq(1, "002371", "正常"),
                slotReq(2, null, null),
                slotReq(3, null, null),
                slotReq(4, null, null),
                slotReq(5, null, null),
                slotReq(6, null, null),
                slotReq(7, null, null),
                slotReq(8, null, null)
        ));

        service.update("tech_vc", req);

        ArgumentCaptor<List<InvestWeeklyOpportunitySlot>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(captor.capture());
        List<InvestWeeklyOpportunitySlot> saved = captor.getValue();
        assertThat(saved.get(0).getStockCode()).isNull();
        assertThat(saved.get(1).getStockCode()).isEqualTo("002371");
    }

    // ══════════════════════════════════════════════════
    // 辅助方法
    // ══════════════════════════════════════════════════

    private InvestWeeklyOpportunitySlot slot(int idx, String code, String reason) {
        InvestWeeklyOpportunitySlot s = new InvestWeeklyOpportunitySlot();
        s.setId((long) (idx + 1));
        s.setPoolType("tech_vc");
        s.setSlotIndex(idx);
        s.setStockCode(code);
        s.setReason(reason);
        s.setUpdatedAt(java.time.LocalDateTime.of(2026, 6, 29, 21, 0));
        return s;
    }

    private WeeklyOpportunityUpdateRequest.SlotItem slotReq(int idx, String code, String reason) {
        WeeklyOpportunityUpdateRequest.SlotItem item = new WeeklyOpportunityUpdateRequest.SlotItem();
        item.setSlotIndex(idx);
        item.setStockCode(code);
        item.setReason(reason);
        return item;
    }

    private List<WeeklyOpportunityUpdateRequest.SlotItem> nineEmpty() {
        List<WeeklyOpportunityUpdateRequest.SlotItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) items.add(slotReq(i, null, null));
        return items;
    }

    private InvestStockPool stock(String code, String name) {
        InvestStockPool p = new InvestStockPool();
        p.setId(code.hashCode());
        p.setStockCode(code);
        p.setStockName(name);
        p.setPoolType("test");
        return p;
    }
}
