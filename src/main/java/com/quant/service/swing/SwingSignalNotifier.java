package com.quant.service.swing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quant.entity.SwingSignal;
import com.quant.entity.SwingWatchlist;
import com.quant.entity.User;
import com.quant.repository.SwingSignalRepository;
import com.quant.repository.SwingWatchlistRepository;
import com.quant.repository.UserRepository;
import com.quant.service.notification.NotificationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class SwingSignalNotifier {

  private final SwingSignalRepository signalRepository;
  private final SwingWatchlistRepository watchRepository;
  private final UserRepository userRepository;
  private final NotificationService notificationService;

  public record EmitResult(SwingSignal signal, boolean created) {}

  @Transactional
  public EmitResult emit(
      SwingWatchlist watch,
      String signalType,
      String level,
      String title,
      String content,
      BigDecimal triggerPrice,
      String suggestAction,
      Integer suggestShares,
      BigDecimal suggestStop,
      Long setupId,
      Long positionId,
      boolean dailyDedupe) {
    String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    String hourBucket =
        dailyDedupe ? day : day + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH"));
    String dedupeKey =
        watch.getUserId() + ":" + watch.getId() + ":" + signalType + ":" + hourBucket;

    var existing = signalRepository.findByDedupeKey(dedupeKey);
    if (existing.isPresent()) {
      return new EmitResult(existing.get(), false);
    }

    SwingSignal signal = new SwingSignal();
    signal.setWatchId(watch.getId());
    signal.setUserId(watch.getUserId());
    signal.setSetupId(setupId);
    signal.setPositionId(positionId);
    signal.setSignalType(signalType);
    signal.setLevel(level);
    signal.setTitle(title);
    signal.setContent(content);
    signal.setTriggerPrice(triggerPrice);
    signal.setSuggestAction(suggestAction);
    signal.setSuggestShares(suggestShares);
    signal.setSuggestStop(suggestStop);
    signal.setStatus(SwingConstants.SIGNAL_PENDING);
    signal.setDedupeKey(dedupeKey);

    try {
      signal = signalRepository.save(signal);
    } catch (DataIntegrityViolationException e) {
      return new EmitResult(signalRepository.findByDedupeKey(dedupeKey).orElse(signal), false);
    }

    String sendKey = resolveSendKey(watch);
    boolean sent = notificationService.sendServerChan(title, content, sendKey);
    if (!sent && (sendKey == null || sendKey.isBlank())) {
      // fallback 全局 key
      sent = notificationService.sendServerChan(title, content);
    }
    signal.setStatus(SwingConstants.SIGNAL_NOTIFIED);
    signal.setNotifiedAt(LocalDateTime.now());
    signalRepository.save(signal);

    watch.setLastSignalAt(LocalDateTime.now());
    watchRepository.save(watch);

    log.info(
        "swing signal emitted: watchId={}, type={}, sent={}, id={}",
        watch.getId(),
        signalType,
        sent,
        signal.getId());
    return new EmitResult(signal, true);
  }

  private String resolveSendKey(SwingWatchlist watch) {
    if (watch.getServerchanSendKey() != null && !watch.getServerchanSendKey().isBlank()) {
      return watch.getServerchanSendKey();
    }
    return userRepository
        .findById(watch.getUserId())
        .map(User::getServerchanSendKey)
        .filter(k -> k != null && !k.isBlank())
        .orElse(null);
  }
}
