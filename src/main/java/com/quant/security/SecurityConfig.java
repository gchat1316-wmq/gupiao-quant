package com.quant.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(List.of("*"));
        cfg.setAllowedMethods(List.of("*"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setExposedHeaders(List.of("Authorization"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // ===== 静态资源（首页/页面/CSS/JS/图片/上传文件）全部公开 =====
                .requestMatchers("/", "/index.html", "/favicon.ico", "/error").permitAll()
                .requestMatchers("/*.html").permitAll()
                .requestMatchers("/css/**", "/js/**", "/lib/**", "/images/**", "/img/**").permitAll()
                .requestMatchers("/uploads/**", "/static/**", "/assets/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/*.png", "/*.jpg", "/*.jpeg", "/*.gif", "/*.svg", "/*.ico", "/*.webp").permitAll()
                // ===== 公开 API =====
                .requestMatchers(HttpMethod.GET, "/api/quote/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/stock/search").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/stock/info/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/news/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/analysis/**").permitAll()
                // 龙江股票池读取公开
                .requestMatchers(HttpMethod.GET, "/api/invest/pool").permitAll()
                // SOP 体检 + 大阳线战法读公开（只放查询，不放写）
                .requestMatchers(HttpMethod.GET, "/api/invest/sop/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/invest/big-yang/**").permitAll()
                // 认证接口
                .requestMatchers("/api/auth/**").permitAll()
                // 统计上报（前端静默调用，无需认证）
                .requestMatchers(HttpMethod.POST, "/api/stats/page-view").permitAll()
                // actuator
                .requestMatchers("/actuator/**").permitAll()
                // 其他请求需要认证
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
