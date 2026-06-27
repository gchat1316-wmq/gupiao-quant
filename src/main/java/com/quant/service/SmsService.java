package com.quant.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;
import java.util.Random;

/**
 * 华信短信服务
 */
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
}
