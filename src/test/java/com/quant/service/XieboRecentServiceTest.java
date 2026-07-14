package com.quant.service;

import com.quant.dto.xiebo.RecentNoteDto;
import com.quant.dto.xiebo.RecentWatchDto;
import com.quant.entity.InvestXieboRecentWatch;
import com.quant.entity.InvestXieboStockNote;
import com.quant.repository.InvestXieboRecentWatchRepository;
import com.quant.repository.InvestXieboStockNoteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class XieboRecentServiceTest {

    @Mock InvestXieboRecentWatchRepository watchRepo;
    @Mock InvestXieboStockNoteRepository noteRepo;
    @Mock AStockDataQuoteService quoteService;
    @InjectMocks XieboRecentService service;

    private InvestXieboRecentWatch makeWatch(String code, String name, String type) {
        InvestXieboRecentWatch w = new InvestXieboRecentWatch();
        w.setStockCode(code);
        w.setStockName(name);
        w.setType(type);
        w.setCreatedAt(LocalDateTime.now());
        return w;
    }

    private AStockDataQuoteService.QuoteSnapshot quote(String code, String price, String prev) {
        return new AStockDataQuoteService.QuoteSnapshot(
                code, new BigDecimal(price), new BigDecimal(prev),
                null, LocalDateTime.now(), "tencent");
    }

    @Test
    void listAll_noType_returnsAllWithQuoteAndHasNote() {
        when(watchRepo.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(makeWatch("600519", "贵州茅台", "质量优选"),
                                    makeWatch("002594", "比亚迪", "科技AI")));
        when(noteRepo.findById("600519")).thenReturn(Optional.of(new InvestXieboStockNote()));
        when(noteRepo.findById("002594")).thenReturn(Optional.empty());
        Map<String, AStockDataQuoteService.QuoteSnapshot> qMap = new HashMap<>();
        qMap.put("600519", quote("600519", "1893.20", "1868.50"));
        qMap.put("002594", quote("002594", "245.50", "240.00"));
        when(quoteService.fetchQuotes(List.of("600519", "002594"))).thenReturn(qMap);

        List<RecentWatchDto> items = service.listAll(null);

        assertThat(items).hasSize(2);
        assertThat(items.get(0).getStockCode()).isEqualTo("600519");
        assertThat(items.get(0).getCurrentPrice()).isEqualByComparingTo("1893.20");
        assertThat(items.get(0).getHasNote()).isTrue();
        assertThat(items.get(1).getStockCode()).isEqualTo("002594");
        assertThat(items.get(1).getHasNote()).isFalse();
    }

    @Test
    void listAll_withType_filtersRepo() {
        when(watchRepo.findByTypeOrderByCreatedAtDesc("科技AI"))
                .thenReturn(List.of(makeWatch("002594", "比亚迪", "科技AI")));
        when(quoteService.fetchQuotes(List.of("002594"))).thenReturn(Map.of());

        List<RecentWatchDto> items = service.listAll("科技AI");

        verify(watchRepo).findByTypeOrderByCreatedAtDesc("科技AI");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getCurrentPrice()).isNull();
    }

    @Test
    void listAll_emptyRepo_returnsEmptyList() {
        when(watchRepo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());
        assertThat(service.listAll(null)).isEmpty();
    }

    @Test
    void listAll_quoteFetchFails_returnsNullPrices() {
        when(watchRepo.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(makeWatch("600519", "贵州茅台", "质量优选")));
        when(noteRepo.findById("600519")).thenReturn(Optional.empty());
        when(quoteService.fetchQuotes(List.of("600519")))
                .thenThrow(new RuntimeException("network down"));

        List<RecentWatchDto> items = service.listAll(null);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).getCurrentPrice()).isNull();
        assertThat(items.get(0).getHasNote()).isFalse();
    }

    @Test
    void getNote_existing_returnsDto() {
        InvestXieboStockNote n = new InvestXieboStockNote();
        n.setStockCode("600519");
        n.setNoteHtml("<p>好公司</p>");
        n.setUpdatedAt(LocalDateTime.now());
        when(noteRepo.findById("600519")).thenReturn(Optional.of(n));

        RecentNoteDto dto = service.getNote("600519");

        assertThat(dto).isNotNull();
        assertThat(dto.getStockCode()).isEqualTo("600519");
        assertThat(dto.getNoteHtml()).isEqualTo("<p>好公司</p>");
    }

    @Test
    void getNote_missing_returnsNull() {
        when(noteRepo.findById("X")).thenReturn(Optional.empty());
        assertThat(service.getNote("X")).isNull();
    }

    @Test
    void getNote_blankCode_throws() {
        assertThatThrownBy(() -> service.getNote(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stockCode");
    }
}
