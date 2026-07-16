package com.quant.controller;

import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.quant.dto.xiebo.ServerchanKeyUpdateRequest;
import com.quant.dto.xiebo.UserSubscriptionDto;
import com.quant.dto.xiebo.UserSubscriptionUpsertRequest;
import com.quant.entity.User;
import com.quant.repository.UserRepository;
import com.quant.security.UserPrincipal;
import com.quant.service.XieboRecentSubscriptionService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/me")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class UserXieboRecentController {

  private final XieboRecentSubscriptionService subscriptionService;
  private final UserRepository userRepository;

  @GetMapping("/recent/subscriptions")
  @PreAuthorize("isAuthenticated()")
  public List<UserSubscriptionDto> list(@AuthenticationPrincipal UserPrincipal me) {
    return subscriptionService.listByUser(me.getId());
  }

  @PutMapping("/recent/subscriptions/{stockCode}")
  @PreAuthorize("isAuthenticated()")
  public Map<String, Object> upsert(
      @AuthenticationPrincipal UserPrincipal me,
      @PathVariable String stockCode,
      @RequestBody @Valid UserSubscriptionUpsertRequest req) {
    UserSubscriptionDto dto = subscriptionService.upsert(me.getId(), stockCode, req);
    return Map.of("ok", true, "subscriptionId", dto.getId(), "enabled", dto.getEnabled());
  }

  @PostMapping("/recent/subscriptions/{stockCode}/reset-alerts")
  @PreAuthorize("isAuthenticated()")
  public Map<String, Object> resetAlerts(
      @AuthenticationPrincipal UserPrincipal me, @PathVariable String stockCode) {
    subscriptionService.resetAlerts(me.getId(), stockCode);
    return Map.of("ok", true);
  }

  @PutMapping("/serverchan-key")
  @PreAuthorize("isAuthenticated()")
  public Map<String, Object> setSckey(
      @AuthenticationPrincipal UserPrincipal me,
      @RequestBody @Valid ServerchanKeyUpdateRequest req) {
    User u =
        userRepository
            .findById(me.getId())
            .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
    u.setServerchanSendKey(req.getServerchanSendKey());
    userRepository.save(u);
    return Map.of("ok", true);
  }
}
