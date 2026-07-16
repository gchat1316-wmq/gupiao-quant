package com.quant.service.xieboinvest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.quant.dto.xieboinvest.XieboWeeklyOpportunitySlotDTO;
import com.quant.dto.xieboinvest.XieboWeeklyOpportunityUpdateRequest;
import com.quant.entity.InvestXieboWatchlist;
import com.quant.entity.XieboWeeklyOpportunitySlot;
import com.quant.repository.InvestXieboWatchlistRepository;
import com.quant.repository.XieboWeeklyOpportunitySlotRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("XieboWeeklyOpportunityService")
class XieboWeeklyOpportunityServiceTest {

  @Mock private XieboWeeklyOpportunitySlotRepository slotRepo;
  @Mock private InvestXieboWatchlistRepository watchlistRepo;

  private XieboWeeklyOpportunityService service;

  @BeforeEach
  void setUp() {
    service = new XieboWeeklyOpportunityService(slotRepo, watchlistRepo);
  }

  @Test
  @DisplayName("get - 9 个 slot 顺序递增，空槽 stockCode 为 null")
  void getReturnsNineSlotsInOrder() {
    when(watchlistRepo.findAllByOrderByDisplayOrderAscCreatedAtAsc()).thenReturn(List.of());
    when(slotRepo.findByPoolTypeOrderBySlotIndexAsc("watch"))
        .thenReturn(List.of(slot(0, "002371"), slot(1, "600519")));

    List<XieboWeeklyOpportunitySlotDTO> out = service.get("watch");

    assertThat(out).hasSize(9);
    assertThat(out.get(0).getStockCode()).isEqualTo("002371");
    assertThat(out.get(0).getSlotIndex()).isEqualTo(0);
    assertThat(out.get(1).getStockCode()).isEqualTo("600519");
    // 2~8 为空
    for (int i = 2; i <= 8; i++) {
      assertThat(out.get(i).getSlotIndex()).isEqualTo(i);
      assertThat(out.get(i).getStockCode()).isNull();
    }
  }

  @Test
  @DisplayName("get - 未知 poolType 抛 IllegalArgumentException")
  void getUnknownPoolTypeThrows() {
    assertThatThrownBy(() -> service.get("invalid"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的 poolType");
  }

  @Test
  @DisplayName("update - 校验 slotIndex 越界抛异常")
  void updateOutOfRangeThrows() {
    XieboWeeklyOpportunityUpdateRequest req = new XieboWeeklyOpportunityUpdateRequest();
    List<XieboWeeklyOpportunityUpdateRequest.SlotItem> items = new ArrayList<>();
    for (int i = 0; i < 9; i++) {
      XieboWeeklyOpportunityUpdateRequest.SlotItem it =
          new XieboWeeklyOpportunityUpdateRequest.SlotItem();
      it.setSlotIndex(i);
      items.add(it);
    }
    // 替换最后一个为 9（越界）
    items.get(8).setSlotIndex(9);
    req.setSlots(items);

    assertThatThrownBy(() -> service.update("watch", req))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("越界");
    verify(slotRepo, never()).deleteByPoolType(anyString());
  }

  @Test
  @DisplayName("update - 校验 slotIndex 重复抛异常")
  void updateDuplicateSlotIndexThrows() {
    XieboWeeklyOpportunityUpdateRequest req = new XieboWeeklyOpportunityUpdateRequest();
    List<XieboWeeklyOpportunityUpdateRequest.SlotItem> items = new ArrayList<>();
    for (int i = 0; i < 9; i++) {
      XieboWeeklyOpportunityUpdateRequest.SlotItem it =
          new XieboWeeklyOpportunityUpdateRequest.SlotItem();
      it.setSlotIndex(i);
      items.add(it);
    }
    items.get(5).setSlotIndex(3);
    req.setSlots(items);

    assertThatThrownBy(() -> service.update("focus", req))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("重复");
    verify(slotRepo, never()).deleteByPoolType(anyString());
  }

  @Test
  @DisplayName("update - slot 数量不是 9 抛异常")
  void updateWrongSlotCountThrows() {
    XieboWeeklyOpportunityUpdateRequest req = new XieboWeeklyOpportunityUpdateRequest();
    List<XieboWeeklyOpportunityUpdateRequest.SlotItem> items = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      XieboWeeklyOpportunityUpdateRequest.SlotItem it =
          new XieboWeeklyOpportunityUpdateRequest.SlotItem();
      it.setSlotIndex(i);
      items.add(it);
    }
    req.setSlots(items);

    assertThatThrownBy(() -> service.update("watch", req))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("slots 数量");
  }

  @Test
  @DisplayName("update - 合法 9 slot → 先删后插，stockName 联动 watchlist")
  void updateHappyPathSavesAndLinksName() {
    InvestXieboWatchlist w = new InvestXieboWatchlist();
    w.setStockCode("002371");
    w.setStockName("北方华创");
    when(watchlistRepo.findAllByOrderByDisplayOrderAscCreatedAtAsc()).thenReturn(List.of(w));

    XieboWeeklyOpportunityUpdateRequest req = new XieboWeeklyOpportunityUpdateRequest();
    List<XieboWeeklyOpportunityUpdateRequest.SlotItem> items = new ArrayList<>();
    for (int i = 0; i < 9; i++) {
      XieboWeeklyOpportunityUpdateRequest.SlotItem it =
          new XieboWeeklyOpportunityUpdateRequest.SlotItem();
      it.setSlotIndex(i);
      if (i == 0) {
        it.setStockCode(" 002371 "); // 含空白，应被 trim
        it.setReason(" PEG 0.85 极度低估 ");
      } else if (i == 3) {
        it.setStockCode(""); // 空串视作 null
      }
      items.add(it);
    }
    req.setSlots(items);

    service.update("focus", req);

    verify(slotRepo).deleteByPoolType("focus");
    verify(slotRepo, times(2)).flush(); // service 中先 delete + flush，再 saveAll + flush
    ArgumentCaptor<List<XieboWeeklyOpportunitySlot>> captor = ArgumentCaptor.forClass(List.class);
    verify(slotRepo).saveAll(captor.capture());
    List<XieboWeeklyOpportunitySlot> saved = captor.getValue();
    assertThat(saved).hasSize(9);
    assertThat(saved.get(0).getStockCode()).isEqualTo("002371"); // 已 trim
    assertThat(saved.get(0).getReason()).isEqualTo("PEG 0.85 极度低估");
    assertThat(saved.get(3).getStockCode()).isNull(); // 空串 → null
  }

  @Test
  @DisplayName("listAll - 串联三个分类返回 27 个 slot")
  void listAllAggregatesAllPoolTypes() {
    when(watchlistRepo.findAllByOrderByDisplayOrderAscCreatedAtAsc()).thenReturn(List.of());
    when(slotRepo.findByPoolTypeOrderBySlotIndexAsc("watch")).thenReturn(List.of());
    when(slotRepo.findByPoolTypeOrderBySlotIndexAsc("focus")).thenReturn(List.of());
    when(slotRepo.findByPoolTypeOrderBySlotIndexAsc("explore")).thenReturn(List.of());

    List<XieboWeeklyOpportunitySlotDTO> out = service.listAll();

    assertThat(out).hasSize(27);
    long watchCount = out.stream().filter(s -> "watch".equals(s.getPoolType())).count();
    long focusCount = out.stream().filter(s -> "focus".equals(s.getPoolType())).count();
    long exploreCount = out.stream().filter(s -> "explore".equals(s.getPoolType())).count();
    assertThat(watchCount).isEqualTo(9);
    assertThat(focusCount).isEqualTo(9);
    assertThat(exploreCount).isEqualTo(9);
  }

  private static XieboWeeklyOpportunitySlot slot(int idx, String code) {
    XieboWeeklyOpportunitySlot s = new XieboWeeklyOpportunitySlot();
    s.setSlotIndex(idx);
    s.setStockCode(code);
    s.setPoolType("watch");
    return s;
  }
}
