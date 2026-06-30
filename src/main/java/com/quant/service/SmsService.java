package com.quant.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;
import java.util.Random;

/**
 * 华信短信服务
 */
@Slf4j
@Service
public class SmsService {

    @Value("${app.sms.huaxin.url:https://http.yunsms.cn/sms/send.do}")
    private String smsUrl;

    @Value("${app.sms.huaxin.username:}")
    private String username;

    @Value("${app.sms.huaxin.password:}")
    private String password;

    @Value("${app.sms.huaxin.product-id:}")
    private String productId;

    private final Random random = new Random();

    /** 生成6位数字验证码 */
    public String generateCode() {
        return String.format("%06d", random.nextInt(1_000_000));
    }

    /**
     * 是否为 mock 模式（未配置华信账号）。
     * 用于 AuthController 决定是否在 /send-code 响应里回传 code 字段
     * —— 仅 dev/mock 模式回传，方便前端自动回填验证码到输入框；
     * 真服务上线后不再回传，前端逻辑自动退化为「用户去查短信」。
     */
    public boolean isMock() {
        return username == null || username.isBlank();
    }

    /**
     * 发送验证码到手机号
     * @return 发送成功时返回生成的验证码（方便开发环境直接返回）
     *         发送失败时返回 null
     */
    public String sendCode(String phone, String code) {
        String content = "【gupiao-quant】您的验证码为" + code + "，5分钟内有效，请勿泄露。";

        // 未配置华信账号时，打印到日志（开发环境）
        if (username == null || username.isBlank()) {
            System.out.println("[华信SMS Mock] phone=" + phone + " code=" + code);
            return code;
        }

        try {
            String response = WebClient.builder()
                    .build()
                    .get()
                    .uri(smsUrl, uri -> uri
                            .queryParam("username", username)
                            .queryParam("password", password)
                            .queryParam("productid", productId)
                            .queryParam("phone", phone)
                            .queryParam("content", content)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(10));

            if (response != null && (response.contains("success") || response.trim().equals("1"))) {
                return code;
            }
            System.out.println("[华信SMS Error] response=" + response);
            return null;
        } catch (Exception e) {
            System.out.println("[华信SMS Exception] " + e.getMessage());
            return null;
        }
    }

    /**
     * 给指定手机号发送任意文本短信（用于价格告警等业务通知）。
     * @return 成功返回 true，失败返回 false
     */
    public boolean sendAlarm(String phone, String text) {
        if (phone == null || phone.isBlank()) return false;
        if (text == null || text.isBlank()) return false;

        // 未配置华信账号时，开发环境打印到日志
        if (username == null || username.isBlank()) {
            log.info("[华信SMS Mock] phone={} text={}", phone, text);
            return true;
        }

        try {
            String response = WebClient.builder()
                    .build()
                    .get()
                    .uri(smsUrl, uri -> uri
                            .queryParam("username", username)
                            .queryParam("password", password)
                            .queryParam("productid", productId)
                            .queryParam("phone", phone)
                            .queryParam("content", text)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(10));

            if (response != null && (response.contains("success") || response.trim().equals("1"))) {
                return true;
            }
            log.warn("[华信SMS Error] phone={} response={}", phone, response);
            return false;
        } catch (Exception e) {
            log.warn("[华信SMS Exception] phone={} err={}", phone, e.getMessage());
            return false;
        }
    }
}
