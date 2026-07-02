package com.quant.service;

import com.quant.dto.invest.WeeklyOpportunitySlotDTO;
import com.quant.dto.invest.WeeklyOpportunityUpdateRequest;
import com.quant.entity.InvestStockPool;
import com.quant.entity.InvestWeeklyOpportunitySlot;
import com.quant.repository.InvestStockPoolRepository;
import com.quant.repository.InvestWeeklyOpportunitySlotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖 InvestWeeklyOpportunityService 的 imageUrl 扩展点：
 * - update() 持久化 imageUrl
 * - get() 透传 imageUrl 到 DTO
 * - setSlotImage() 写图到文件系统 + 落库
 * - clearSlotImage() 清空 imageUrl
 * - 文件类型校验
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InvestWeeklyOpportunityService - imageUrl 支持")
class InvestWeeklyOpportunityServiceTest {

    @Mock
    private InvestWeeklyOpportunitySlotRepository slotRepo;

    @Mock
    private InvestStockPoolRepository stockPoolRepo;

    @TempDir
    Path tempUploadDir;

    private InvestWeeklyOpportunityService service;

    @BeforeEach
    void setUp() {
        service = new InvestWeeklyOpportunityService(slotRepo, stockPoolRepo);
        ReflectionTestUtils.setField(service, "uploadDir", tempUploadDir.toString());
    }

    // ══════════════════════════════════════════════════
    // update() 持久化 imageUrl
    // ══════════════════════════════════════════════════

    @Test
    @DisplayName("update: imageUrl 持久化到 slot")
    void update_persistsImageUrl() {
        when(stockPoolRepo.findByPoolTypeOrderByCreatedAtDesc(anyString())).thenReturn(List.of());

        WeeklyOpportunityUpdateRequest req = new WeeklyOpportunityUpdateRequest();
        List<WeeklyOpportunityUpdateRequest.SlotItem> items = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            WeeklyOpportunityUpdateRequest.SlotItem item = new WeeklyOpportunityUpdateRequest.SlotItem();
            item.setSlotIndex(i);
            item.setStockCode(i == 0 ? "688256" : null);
            item.setReason(i == 0 ? "测试" : null);
            item.setImageUrl(i == 0 ? "/uploads/weekly-opportunity/tech_ai/0/abc.png" : null);
            items.add(item);
        }
        req.setSlots(items);

        ArgumentCaptor<List<InvestWeeklyOpportunitySlot>> captor = ArgumentCaptor.forClass(List.class);

        service.update("tech_ai", req);

        verify(slotRepo).saveAll(captor.capture());
        List<InvestWeeklyOpportunitySlot> saved = captor.getValue();
        assertThat(saved).hasSize(9);
        assertThat(saved.get(0).getImageUrl()).isEqualTo("/uploads/weekly-opportunity/tech_ai/0/abc.png");
        for (int i = 1; i < 9; i++) {
            assertThat(saved.get(i).getImageUrl()).isNull();
        }
    }

    @Test
    @DisplayName("update: 空字符串 imageUrl 视作 null")
    void update_blankImageUrlStoredAsNull() {
        when(stockPoolRepo.findByPoolTypeOrderByCreatedAtDesc(anyString())).thenReturn(List.of());

        WeeklyOpportunityUpdateRequest req = new WeeklyOpportunityUpdateRequest();
        List<WeeklyOpportunityUpdateRequest.SlotItem> items = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            WeeklyOpportunityUpdateRequest.SlotItem item = new WeeklyOpportunityUpdateRequest.SlotItem();
            item.setSlotIndex(i);
            item.setImageUrl("   ");
            items.add(item);
        }
        req.setSlots(items);

        ArgumentCaptor<List<InvestWeeklyOpportunitySlot>> captor = ArgumentCaptor.forClass(List.class);
        service.update("tech_ai", req);

        verify(slotRepo).saveAll(captor.capture());
        assertThat(captor.getValue()).allSatisfy(s -> assertThat(s.getImageUrl()).isNull());
    }

    @Test
    @DisplayName("update: userStockName 持久化到 slot")
    void update_persistsUserStockName() {
        when(stockPoolRepo.findByPoolTypeOrderByCreatedAtDesc(anyString())).thenReturn(List.of());

        WeeklyOpportunityUpdateRequest req = new WeeklyOpportunityUpdateRequest();
        List<WeeklyOpportunityUpdateRequest.SlotItem> items = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            WeeklyOpportunityUpdateRequest.SlotItem item = new WeeklyOpportunityUpdateRequest.SlotItem();
            item.setSlotIndex(i);
            item.setStockCode("999999");
            item.setUserStockName(i == 0 ? "手工填的名称" : null);
            items.add(item);
        }
        req.setSlots(items);

        ArgumentCaptor<List<InvestWeeklyOpportunitySlot>> captor = ArgumentCaptor.forClass(List.class);
        service.update("tech_ai", req);

        verify(slotRepo).saveAll(captor.capture());
        List<InvestWeeklyOpportunitySlot> saved = captor.getValue();
        assertThat(saved.get(0).getUserStockName()).isEqualTo("手工填的名称");
        for (int i = 1; i < 9; i++) {
            assertThat(saved.get(i).getUserStockName()).isNull();
        }
    }

    // ══════════════════════════════════════════════════
    // get() 透传 imageUrl
    // ══════════════════════════════════════════════════

    @Test
    @DisplayName("get: slot 带 imageUrl 时 DTO 也带")
    void get_returnsImageUrlWhenSet() {
        InvestWeeklyOpportunitySlot row = new InvestWeeklyOpportunitySlot();
        row.setPoolType("tech_ai");
        row.setSlotIndex(3);
        row.setStockCode("688256");
        row.setReason("AI 算力");
        row.setImageUrl("/uploads/weekly-opportunity/tech_ai/3/x.png");

        InvestStockPool stock = new InvestStockPool();
        stock.setStockCode("688256");
        stock.setStockName("寒武纪");

        when(slotRepo.findByPoolTypeOrderBySlotIndexAsc("tech_ai")).thenReturn(List.of(row));
        when(stockPoolRepo.findByPoolTypeOrderByCreatedAtDesc("tech_ai")).thenReturn(List.of(stock));

        List<WeeklyOpportunitySlotDTO> result = service.get("tech_ai");

        assertThat(result).hasSize(9);
        assertThat(result.get(3).getStockCode()).isEqualTo("688256");
        assertThat(result.get(3).getStockName()).isEqualTo("寒武纪");
        assertThat(result.get(3).getImageUrl()).isEqualTo("/uploads/weekly-opportunity/tech_ai/3/x.png");
    }

    @Test
    @DisplayName("get: userStockName 在 stockCode 不在池中时透传到 DTO")
    void get_returnsUserStockNameWhenNotInPool() {
        InvestWeeklyOpportunitySlot row = new InvestWeeklyOpportunitySlot();
        row.setPoolType("tech_ai");
        row.setSlotIndex(5);
        row.setStockCode("999999");
        row.setUserStockName("某只外部股票");
        row.setReason("外部挖掘");

        when(slotRepo.findByPoolTypeOrderBySlotIndexAsc("tech_ai")).thenReturn(List.of(row));
        when(stockPoolRepo.findByPoolTypeOrderByCreatedAtDesc("tech_ai")).thenReturn(List.of());

        List<WeeklyOpportunitySlotDTO> result = service.get("tech_ai");

        assertThat(result).hasSize(9);
        assertThat(result.get(5).getStockCode()).isEqualTo("999999");
        assertThat(result.get(5).getStockName()).isNull();  // 联动不到
        assertThat(result.get(5).getUserStockName()).isEqualTo("某只外部股票");
    }

    // ══════════════════════════════════════════════════
    // setSlotImage()
    // ══════════════════════════════════════════════════

    @Test
    @DisplayName("setSlotImage: 写图到文件系统 + 落库 imageUrl")
    void setSlotImage_writesToExistingSlot() throws Exception {
        InvestWeeklyOpportunitySlot row = new InvestWeeklyOpportunitySlot();
        row.setPoolType("tech_ai");
        row.setSlotIndex(0);
        row.setStockCode("688256");
        when(slotRepo.findByPoolTypeAndSlotIndex("tech_ai", 0)).thenReturn(Optional.of(row));

        MockMultipartFile file = new MockMultipartFile(
                "file", "shot.png", "image/png", "fake-png-bytes".getBytes());

        String url = service.setSlotImage("tech_ai", 0, file);

        assertThat(url).startsWith("/uploads/weekly-opportunity/tech_ai/0/").endsWith(".png");
        assertThat(row.getImageUrl()).isEqualTo(url);
    }

    @Test
    @DisplayName("setSlotImage: slot 不存在时新建并写入")
    void setSlotImage_createsSlotIfMissing() throws Exception {
        when(slotRepo.findByPoolTypeAndSlotIndex("innovative_drug", 5)).thenReturn(Optional.empty());
        ArgumentCaptor<InvestWeeklyOpportunitySlot> captor = ArgumentCaptor.forClass(InvestWeeklyOpportunitySlot.class);

        MockMultipartFile file = new MockMultipartFile(
                "file", "p.png", "image/png", "x".getBytes());

        String url = service.setSlotImage("innovative_drug", 5, file);

        verify(slotRepo).save(captor.capture());
        InvestWeeklyOpportunitySlot saved = captor.getValue();
        assertThat(saved.getPoolType()).isEqualTo("innovative_drug");
        assertThat(saved.getSlotIndex()).isEqualTo(5);
        assertThat(saved.getImageUrl()).isEqualTo(url);
    }

    @Test
    @DisplayName("setSlotImage: 非图片文件拒绝")
    void setSlotImage_rejectsNonImageFile() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "evil.exe", "application/octet-stream", "MZ".getBytes());

        assertThatThrownBy(() -> service.setSlotImage("tech_ai", 0, file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JPG/PNG/WebP");

        verify(slotRepo, never()).save(any());
    }

    // ══════════════════════════════════════════════════
    // clearSlotImage()
    // ══════════════════════════════════════════════════

    @Test
    @DisplayName("clearSlotImage: 清空 imageUrl")
    void clearSlotImage_setsNull() {
        InvestWeeklyOpportunitySlot row = new InvestWeeklyOpportunitySlot();
        row.setPoolType("tech_ai");
        row.setSlotIndex(2);
        row.setImageUrl("/uploads/old.png");
        when(slotRepo.findByPoolTypeAndSlotIndex("tech_ai", 2)).thenReturn(Optional.of(row));

        service.clearSlotImage("tech_ai", 2);

        assertThat(row.getImageUrl()).isNull();
    }

    @Test
    @DisplayName("clearSlotImage: slot 不存在时不报错")
    void clearSlotImage_noopWhenMissing() {
        when(slotRepo.findByPoolTypeAndSlotIndex("quality", 8)).thenReturn(Optional.empty());

        service.clearSlotImage("quality", 8);

        verify(slotRepo, never()).save(any());
    }
}
