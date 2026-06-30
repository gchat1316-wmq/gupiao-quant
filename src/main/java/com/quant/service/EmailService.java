package com.quant.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Random;

/**
 * 邮件服务。
 *
 * Dev 环境（未配 SMTP_HOST）时直接打印验证码到 stdout，便于本地调试。
 * Prod 环境配置 SMTP_HOST/SMTP_PORT/SMTP_USERNAME/SMTP_PASSWORD/SMTP_FROM 后，
 * 通过注入的 WebClient 调用 SMTP 网关。
 */
@Service
public class EmailService {

    private final WebClient webClient;
    private final Random random = new Random();

    @Value("${app.email.smtp.host:}")
    private String smtpHost;

    @Value("${app.email.smtp.port:587}")
    private int smtpPort;

    @Value("${app.email.smtp.username:}")
    private String smtpUsername;

    @Value("${app.email.smtp.password:}")
    private String smtpPassword;

    @Value("${app.email.smtp.from:no-reply@gupiao-quant.local}")
    private String smtpFrom;

    @Value("${app.email.smtp.api-url:}")
    private String smtpApiUrl;

    public EmailService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    /** 生成 6 位数字验证码 */
    public String generateCode() {
        return String.format("%06d", random.nextInt(1_000_000));
    }

    /**
     * 是否为 mock 模式（未配置 SMTP 主机）。
     * 用于 AuthController 决定是否在 /send-email-code 响应里回传 code 字段
     * —— 仅 dev/mock 模式回传，方便前端自动回填验证码到输入框；
     * 真服务上线后不再回传，前端逻辑自动退化为「用户去查邮箱」。
     */
    public boolean isMock() {
        return smtpHost == null || smtpHost.isBlank();
    }

    /**
     * 发送验证码到邮箱。
     * @return 发送成功时返回 code；失败时返回 null
     */
    public String sendCode(String email, String code) {
        // 未配置 SMTP 时，dev 模式直接打印
        if (smtpHost == null || smtpHost.isBlank()) {
            System.out.println("[Email Mock] to=" + email + " code=" + code);
            return code;
        }

        // 配了 API URL（推荐）：用 HTTP 调 SMTP 网关
        if (smtpApiUrl != null && !smtpApiUrl.isBlank()) {
            return sendViaApi(email, code);
        }

        // 否则按 SMTP 协议直接连接（简化处理：调网关）
        return sendViaApi(email, code);
    }

    private String sendViaApi(String email, String code) {
        try {
            String body = String.format(
                "{\"to\":\"%s\",\"from\":\"%s\",\"subject\":\"【gupiao-quant】登录验证码\",\"body\":\"您的验证码为 %s，5分钟内有效，请勿泄露。\"}",
                email, smtpFrom, code);
            String response = webClient.post()
                    .uri(smtpApiUrl != null && !smtpApiUrl.isBlank() ? smtpApiUrl : defaultApiUrl())
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(10));
            return response != null ? code : null;
        } catch (Exception e) {
            System.out.println("[Email Error] " + e.getMessage());
            return null;
        }
    }

    private String defaultApiUrl() {
        return String.format("http://%s:%d/api/send", smtpHost, smtpPort);
    }
}
