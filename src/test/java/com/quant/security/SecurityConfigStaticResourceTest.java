package com.quant.security;

import com.quant.controller.AuthController;
import com.quant.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * SecurityConfig 静态资源白名单回归测试。
 *
 * 历史教训：2026-06-27 feat(auth) 提交加了 Security 链，
 * 但 permitAll 名单只放行了 /api/**，导致 .html / .css / .js 全部 403。
 * 整个前端页面打不开，但进程不崩，restart.sh 自检通过，
 * 容易误判为"服务挂了"而实际是"服务没给前端开门"。
 *
 * 回归保险：以下路径必须在不携带 token 的情况下返回非 401/403 状态。
 * 关键是绝对不能 401/403——那才是 Security 拦了。
 *
 * 选用 AuthController.class 作为 WebMvcTest 入口是顺手的：
 * 它跟其他 controller 一样存在于 com.quant.controller 包下，
 * WebMvcTest 会自动导入同包的所有 @ControllerAdvice（如有），
 * 再加上 @Import(SecurityConfig.class, ...) 把 Security 链挂上。
 * 业务 bean 通过 @MockBean 隔离掉，本测试只关心 Security 的 permitAll 决策。
 */
@WebMvcTest(controllers = AuthController.class, properties = {
        "app.jwt.secret=test-secret-key-at-least-32-chars-long-for-hs256!"
})
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtTokenProvider.class})
@DisplayName("SecurityConfig 静态资源白名单")
class SecurityConfigStaticResourceTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private com.quant.service.AuthService authService;
    @MockBean
    private UserRepository userRepository;
    @MockBean
    private com.quant.service.wechat.WechatMpService wechatMpService;
    @MockBean
    private com.quant.service.SmsService smsService;
    @MockBean
    private com.quant.service.EmailService emailService;
    @MockBean
    private com.quant.service.wechat.WechatScanSession wechatScanSession;

    /** 状态码既不是 401 也不是 403（即没被 Security 链拒掉） */
    private static final ResultMatcher NOT_SECURITY_BLOCKED = result -> {
        int status = result.getResponse().getStatus();
        if (status == 401 || status == 403) {
            throw new AssertionError("Expected NOT 401/403 (Security should not block), but got " + status);
        }
    };

    // ── 一级目录的 HTML 页面（裸路径，不带 context-path）──

    @Test
    @DisplayName("根路径 / 必须不被 Security 拦截")
    void rootPathIsPublic() throws Exception {
        mvc.perform(get("/")).andExpect(NOT_SECURITY_BLOCKED);
    }

    @Test
    @DisplayName("/index.html 必须不被 Security 拦截")
    void indexHtmlIsPublic() throws Exception {
        mvc.perform(get("/index.html")).andExpect(NOT_SECURITY_BLOCKED);
    }

    @Test
    @DisplayName("/invest.html 必须不被 Security 拦截（用户反馈的具体页面）")
    void investHtmlIsPublic() throws Exception {
        mvc.perform(get("/invest.html")).andExpect(NOT_SECURITY_BLOCKED);
    }

    @Test
    @DisplayName("/prosperity-strong.html 等业务页面必须不被 Security 拦截")
    void businessPagesArePublic() throws Exception {
        for (String path : new String[]{
                "/prosperity-strong.html",
                "/stock-analysis.html",
                "/study.html",
                "/xiebo-invest.html",
                "/admin-users.html",
                "/tech-ai.html"
        }) {
            mvc.perform(get(path)).andExpect(NOT_SECURITY_BLOCKED);
        }
    }

    // ── 静态资源子目录 ──

    @Test
    @DisplayName("/css/** /js/** /lib/** 必须不被 Security 拦截")
    void staticAssetDirsArePublic() throws Exception {
        for (String path : new String[]{
                "/css/invest.css",
                "/css/app.css",
                "/js/invest.js",
                "/js/app.js",
                "/lib/chart.js"
        }) {
            mvc.perform(get(path)).andExpect(NOT_SECURITY_BLOCKED);
        }
    }

    @Test
    @DisplayName("/uploads/** 用户上传文件必须不被 Security 拦截")
    void uploadsArePublic() throws Exception {
        mvc.perform(get("/uploads/foo.pdf")).andExpect(NOT_SECURITY_BLOCKED);
        mvc.perform(get("/uploads/industry-research/report.html")).andExpect(NOT_SECURITY_BLOCKED);
    }

    @Test
    @DisplayName("根级图片资源必须不被 Security 拦截")
    void rootImagesArePublic() throws Exception {
        mvc.perform(get("/donate-qr.png")).andExpect(NOT_SECURITY_BLOCKED);
        mvc.perform(get("/favicon.ico")).andExpect(NOT_SECURITY_BLOCKED);
    }

    // ── 公开 API（顺便锁住，不允许被回退改坏）──

    @Test
    @DisplayName("公开 GET API 必须不被 Security 拦截")
    void publicGetApisArePublic() throws Exception {
        for (String path : new String[]{
                "/api/quote/abc",
                "/api/stock/search",
                "/api/stock/info/600000",
                "/api/news/list",
                "/api/analysis/foo"
        }) {
            mvc.perform(get(path)).andExpect(NOT_SECURITY_BLOCKED);
        }
    }

    @Test
    @DisplayName("POST /api/stats/page-view 必须不被 Security 拦截（前端静默统计）")
    void statsPageViewIsPublic() throws Exception {
        mvc.perform(post("/api/stats/page-view").contentType("application/json").content("{}"))
                .andExpect(NOT_SECURITY_BLOCKED);
    }

    @Test
    @DisplayName("认证接口 /api/auth/** 必须不被 Security 拦截")
    void authEndpointsArePublic() throws Exception {
        // /api/auth/send-code (POST) 是真实的公开认证入口
        mvc.perform(post("/api/auth/send-code").contentType("application/json").content("{}"))
                .andExpect(NOT_SECURITY_BLOCKED);
        // /api/auth/login-code (POST) 同上
        mvc.perform(post("/api/auth/login-code").contentType("application/json").content("{}"))
                .andExpect(NOT_SECURITY_BLOCKED);
    }
}
