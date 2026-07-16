package com.quant.service;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import com.quant.config.NotificationProperties;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class NotificationService {

  private final NotificationProperties props;
  private final RestTemplate restTemplate;

  public NotificationService(NotificationProperties props) {
    this.props = props;
    this.restTemplate = buildRestTemplate(props.getServerchan());
  }

  private static RestTemplate buildRestTemplate(NotificationProperties.ServerChan cfg) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(5_000);
    factory.setReadTimeout(cfg != null ? cfg.getTimeoutSeconds() * 1000 : 5_000);
    return new RestTemplate(factory);
  }

  /** 向后兼容 — 用全局 SCKEY */
  public boolean sendServerChan(String title, String content) {
    NotificationProperties.ServerChan cfg = props.getServerchan();
    if (cfg == null) {
      return false;
    }
    return sendServerChan(title, content, cfg.getSendKey());
  }

  /** 新 — 显式传 SCKEY(订阅级 > 用户级 > 全局级) */
  public boolean sendServerChan(String title, String content, String sendKey) {
    NotificationProperties.ServerChan cfg = props.getServerchan();
    if (cfg == null || !cfg.isEnabled()) {
      log.debug("Server 酱未启用,跳过推送: {}", title);
      return false;
    }
    if (sendKey == null || sendKey.isBlank()) {
      log.warn("Server 酱 sendKey 未配置,跳过推送: {}", title);
      return false;
    }
    try {
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

      MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
      body.add("title", title == null ? "" : title);
      body.add("desp", content == null ? "" : content);

      String url = cfg.getBaseUrl().replaceAll("/+$", "") + "/" + sendKey + ".send";
      HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);

      String resp = restTemplate.postForObject(url, entity, String.class);
      log.info("Server 酱推送成功: title={}, resp={}", title, resp);
      return true;
    } catch (Exception e) {
      log.warn("Server 酱推送失败: title={}, err={}", title, e.getMessage());
      return false;
    }
  }

  /** 仅测试用:返回可被 MockRestServiceServer 绑定的 RestTemplate */
  RestTemplate restTemplateForTest() {
    return restTemplate;
  }
}
