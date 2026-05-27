package com.quant.service;

import com.quant.config.NotificationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
public class NotificationService {

    private final NotificationProperties props;

    public NotificationService(NotificationProperties props) {
        this.props = props;
    }

    /**
     * 通过 Server 酱发送微信消息。
     * @return true 成功
     */
    public boolean sendServerChan(String title, String content) {
        NotificationProperties.ServerChan cfg = props.getServerchan();
        if (!cfg.isEnabled()) {
            log.debug("Server 酱未启用，跳过推送：{}", title);
            return false;
        }
        if (cfg.getSendKey() == null || cfg.getSendKey().isBlank()) {
            log.warn("Server 酱 sendKey 未配置，跳过推送：{}", title);
            return false;
        }
        try {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(5_000);
            factory.setReadTimeout(cfg.getTimeoutSeconds() * 1000);
            RestTemplate rest = new RestTemplate(factory);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("title", title == null ? "" : title);
            body.add("desp", content == null ? "" : content);

            String url = cfg.getBaseUrl().replaceAll("/+$", "") + "/" + cfg.getSendKey() + ".send";
            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);

            String resp = rest.postForObject(url, entity, String.class);
            log.info("Server 酱推送成功: title={}, resp={}", title, resp);
            return true;
        } catch (Exception e) {
            log.warn("Server 酱推送失败：title={}, err={}", title, e.getMessage());
            return false;
        }
    }
}
