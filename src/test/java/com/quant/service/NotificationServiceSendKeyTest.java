package com.quant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import com.quant.config.NotificationProperties;

class NotificationServiceSendKeyTest {

  private NotificationProperties props;
  private NotificationService service;
  private MockRestServiceServer mockServer;

  @BeforeEach
  void setUp() {
    props = new NotificationProperties();
    NotificationProperties.ServerChan cfg = new NotificationProperties.ServerChan();
    cfg.setEnabled(true);
    cfg.setSendKey("global-default-key");
    cfg.setBaseUrl("https://sctapi.ftqq.com");
    cfg.setTimeoutSeconds(5);
    props.setServerchan(cfg);
    service = new NotificationService(props);
    mockServer = MockRestServiceServer.createServer(service.restTemplateForTest());
  }

  @Test
  void sendServerChan_withSendKey_usesSendKeyInsteadOfDefault() {
    mockServer
        .expect(requestTo("https://sctapi.ftqq.com/global-default-key.send"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("{\"code\":0}", MediaType.APPLICATION_JSON));
    boolean ok = service.sendServerChan("t", "c");
    assertThat(ok).isTrue();
    mockServer.verify();
  }

  @Test
  void sendServerChan_withExplicitSendKey_overridesDefault() {
    mockServer
        .expect(requestTo("https://sctapi.ftqq.com/user-specific-key.send"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
        .andRespond(withSuccess("{\"code\":0}", MediaType.APPLICATION_JSON));
    boolean ok = service.sendServerChan("t", "c", "user-specific-key");
    assertThat(ok).isTrue();
    mockServer.verify();
  }

  @Test
  void sendServerChan_blankSendKey_returnsFalseWithoutHttpCall() {
    boolean ok = service.sendServerChan("t", "c", "   ");
    assertThat(ok).isFalse();
    mockServer.verify();
  }
}
