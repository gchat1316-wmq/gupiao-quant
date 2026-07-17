package com.quant.security;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;

@Component
public class JwtTokenProvider {

  @Value("${app.jwt.secret}")
  private String jwtSecret;

  @Value("${app.jwt.expire-hours:24}")
  private int expireHours;

  private SecretKey key;

  @PostConstruct
  public void init() {
    if (jwtSecret == null || jwtSecret.isBlank()) {
      throw new IllegalStateException(
          "app.jwt.secret must be set (env JWT_SECRET); "
              + "StartupConfigValidator should fail-fast, but @PostConstruct runs earlier in some contexts.");
    }
    // 保证 key 至少 256 bit
    String padded = jwtSecret;
    while (padded.getBytes(StandardCharsets.UTF_8).length < 32) {
      padded = padded + padded;
    }
    this.key = Keys.hmacShaKeyFor(padded.getBytes(StandardCharsets.UTF_8));
  }

  public String generate(Long userId, String role) {
    Date now = new Date();
    Date expiry = new Date(now.getTime() + expireHours * 3600 * 1000L);
    return Jwts.builder()
        .subject(String.valueOf(userId))
        .claim("role", role)
        .issuedAt(now)
        .expiration(expiry)
        .signWith(key)
        .compact();
  }

  public Claims parse(String token) {
    if (token == null || token.isBlank()) return null;
    try {
      return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    } catch (JwtException e) {
      return null;
    }
  }

  public Long getUserId(String token) {
    Claims claims = parse(token);
    if (claims == null) return null;
    return Long.parseLong(claims.getSubject());
  }

  public String getRole(String token) {
    Claims claims = parse(token);
    if (claims == null) return null;
    return claims.get("role", String.class);
  }
}
