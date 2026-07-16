package com.quant.service.prosperitystrong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.quant.config.ProsperityStrongProperties;
import com.quant.dto.prosperitystrong.ProviderCapabilityDTO;

@DisplayName("ProsperityDataProviderService")
class ProsperityDataProviderServiceTest {

  @Test
  @DisplayName("保留 a_stock_data 作为可选热点板块数据链路")
  void normalizesAndListsAStockDataProvider() {
    WindAifinMarketClient wind = mock(WindAifinMarketClient.class);
    when(wind.verify())
        .thenReturn(new WindAifinMarketClient.WindCheck(false, false, "not installed"));
    ProsperityDataProviderService service =
        new ProsperityDataProviderService(new ProsperityStrongProperties(), wind);

    List<ProviderCapabilityDTO> capabilities = service.capabilities();

    assertThat(service.normalize("a_stock_data")).isEqualTo("a_stock_data");
    assertThat(capabilities).extracting(ProviderCapabilityDTO::getCode).contains("a_stock_data");
    assertThat(service.providerMessage("a_stock_data")).contains("a-stock-data");
  }
}
