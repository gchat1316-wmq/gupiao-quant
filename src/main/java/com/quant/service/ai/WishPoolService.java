package com.quant.service.ai;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quant.config.NotificationProperties;
import com.quant.dto.wishpool.WishAdminDto;
import com.quant.dto.wishpool.WishPublicDto;
import com.quant.dto.wishpool.WishSubmitRequest;
import com.quant.entity.WishPool;
import com.quant.repository.WishPoolRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 许愿池业务逻辑： 1) 用户提交 → 先入库(status=PENDING, display=false) → 异步推飞书通知 2) 管理员后台 → 分页查询、回复、切换展示、删除 3)
 * 前台公开 → 轮播已 display=true 且有回复的条目
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WishPoolService {

  private final WishPoolRepository wishPoolRepository;
  private final WishPoolNotifier wishPoolNotifier;
  private final NotificationProperties properties;

  // ============================================================
  // 公开 / 用户侧
  // ============================================================

  /** 用户提交许愿：先入库 + 异步推飞书(失败不阻塞) */
  @Transactional
  public WishPool submitWish(WishSubmitRequest request, String ip) {
    if (request == null || request.getWish() == null || request.getWish().trim().isEmpty()) {
      throw new IllegalArgumentException("请输入想要的能力或需求");
    }

    if (!properties.getWishPool().isEnabled()) {
      // 后台开关关闭 → 仍然接受(只入库),仅不通知到飞书
      log.debug("wish pool notifier disabled by config, only persist (page={})", request.getPage());
    }

    String wishText = request.getWish().trim();
    String page =
        request.getPage() == null || request.getPage().isBlank()
            ? "未知页面"
            : request.getPage().trim();
    String email =
        request.getEmail() == null || request.getEmail().isBlank() ? "" : request.getEmail().trim();

    WishPool entity = new WishPool();
    entity.setWish(wishText);
    entity.setPage(page);
    entity.setEmail(email);
    entity.setIp(ip);
    entity.setStatus(WishPool.Status.PENDING);
    entity.setDisplayFlag(Boolean.FALSE);
    wishPoolRepository.save(entity);

    log.info("wish pool submitted: id={} page={} len={}", entity.getId(), page, wishText.length());

    // 异步推飞书（开关 + 实现都在 notifier 内部判）
    wishPoolNotifier.notifyNewWish(entity);

    return entity;
  }

  /** 前台右下角轮播：display=true 且有回复的，按回复时间倒序 */
  @Transactional(readOnly = true)
  public List<WishPublicDto> listPublic(int size) {
    if (size <= 0 || size > 50) size = 20;
    List<WishPool> rows = wishPoolRepository.findPublicDisplay(PageRequest.of(0, size));
    return rows.stream().map(WishPublicDto::of).collect(Collectors.toList());
  }

  // ============================================================
  // 管理员侧
  // ============================================================

  /** 后台分页 + 状态 + 关键字搜索 */
  @Transactional(readOnly = true)
  public Map<String, Object> listForAdmin(
      WishPool.Status status, String keyword, int page, int size) {
    if (page < 0) page = 0;
    if (size <= 0 || size > 100) size = 20;
    String normalizedKeyword = (keyword == null || keyword.isBlank()) ? null : keyword.trim();

    Pageable pageable = PageRequest.of(page, size);
    Page<WishPool> pg = wishPoolRepository.adminSearch(status, normalizedKeyword, pageable);

    List<WishAdminDto> rows =
        pg.getContent().stream().map(WishAdminDto::of).collect(Collectors.toList());

    return Map.of(
        "rows", rows,
        "total", pg.getTotalElements(),
        "page", pg.getNumber(),
        "size", pg.getSize());
  }

  /** 回复：自动填 reply_by / reply_at，status 改为 REPLIED */
  @Transactional
  public WishAdminDto reply(Long id, String reply, String replyBy) {
    if (reply == null || reply.isBlank()) {
      throw new IllegalArgumentException("回复内容不能为空");
    }
    WishPool w = mustGet(id);
    w.setReply(reply.trim());
    w.setReplyBy(replyBy);
    w.setReplyAt(LocalDateTime.now());
    w.setStatus(WishPool.Status.REPLIED);
    return WishAdminDto.of(wishPoolRepository.save(w));
  }

  /** 切换右下角展示 */
  @Transactional
  public WishAdminDto setDisplay(Long id, boolean display) {
    WishPool w = mustGet(id);
    w.setDisplayFlag(display);
    // 一旦取消展示,允许管理员改回 PENDING 重新进入待办队列(只是补充场景)
    if (display
        && w.getReply() != null
        && !w.getReply().isBlank()
        && w.getStatus() != WishPool.Status.REPLIED) {
      w.setStatus(WishPool.Status.REPLIED);
    }
    return WishAdminDto.of(wishPoolRepository.save(w));
  }

  /** 删除 */
  @Transactional
  public void delete(Long id) {
    WishPool w = mustGet(id);
    wishPoolRepository.delete(w);
  }

  /** 计数（后台看板可选） */
  @Transactional(readOnly = true)
  public Map<String, Long> counts() {
    return Map.of(
        "pending", wishPoolRepository.countByStatus(WishPool.Status.PENDING),
        "replied", wishPoolRepository.countByStatus(WishPool.Status.REPLIED),
        "archived", wishPoolRepository.countByStatus(WishPool.Status.ARCHIVED),
        "display",
            wishPoolRepository.findAll().stream()
                .filter(w -> Boolean.TRUE.equals(w.getDisplayFlag()))
                .count());
  }

  private WishPool mustGet(Long id) {
    return wishPoolRepository
        .findById(id)
        .orElseThrow(() -> new NoSuchElementException("wish not found: " + id));
  }
}
