package com.quant.invest;

import com.quant.entity.InvestStockPool;
import com.quant.repository.InvestStockPoolRepository;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.service.InvestPoolSeedService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("InvestPoolSeedService")
class InvestPoolSeedServiceTest {

    @Test
    @DisplayName("只重建科技风投池并按截图顺序设置 displayOrder")
    void replaceTechVcKeepsScreenshotOrder() {
        InvestStockPoolRepository poolRepo = mock(InvestStockPoolRepository.class);
        TradeStockBasicRepository basicRepo = mock(TradeStockBasicRepository.class);
        when(basicRepo.findByStockCode("688668.SH")).thenReturn(Optional.empty());

        InvestPoolSeedService service = new InvestPoolSeedService(poolRepo, basicRepo);

        int inserted = service.replaceTechVcWithScreenshotPool();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InvestStockPool>> captor = ArgumentCaptor.forClass(List.class);
        verify(poolRepo).deleteByPoolTypeOrUpperStockCodeIn(org.mockito.Mockito.eq("tech_vc"),
                org.mockito.ArgumentMatchers.argThat(codes -> codes.contains("301458.SZ")));
        verify(poolRepo).saveAll(captor.capture());
        List<InvestStockPool> rows = captor.getValue();

        assertThat(inserted).isEqualTo(32);
        assertThat(rows).hasSize(32);
        assertThat(rows.subList(0, 17)).extracting(InvestStockPool::getStockName)
                .containsExactly("埃科光电", "裕太微", "路维光电", "鼎通科技", "仕佳光子", "华丰科技", "思瑞浦",
                        "睿创微纳", "金海通", "日联科技", "奕瑞科技", "凯格精机", "东威科技", "芯碁微装",
                        "杰普特", "长川科技", "拓荆科技");
        assertThat(rows.subList(17, 32)).extracting(InvestStockPool::getStockName)
                .containsExactly("长盈通", "思泰克", "奥来德", "钧崴电子", "伟测科技", "快克智能", "晶方科技",
                        "安集科技", "华海清科", "菲利华", "三环集团", "峰岹科技", "蓝特光学", "星宸科技", "广合科技");
        assertThat(rows.get(0).getDisplayOrder()).isEqualTo(10);
        assertThat(rows.get(31).getDisplayOrder()).isEqualTo(320);
    }
}
