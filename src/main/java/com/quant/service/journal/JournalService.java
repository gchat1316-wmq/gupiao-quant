package com.quant.service.journal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quant.dto.journal.*;
import com.quant.entity.InvestPositionFill;
import com.quant.entity.JournalTrade;
import com.quant.repository.InvestPositionFillRepository;
import com.quant.repository.JournalTradeRepository;

import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class JournalService {

  private final JournalTradeRepository repo;
  private final InvestPositionFillRepository fillRepo;

  @Transactional
  public JournalTradeDTO create(JournalTradeCreateRequest req, String username) {
    validate(req);
    JournalTrade j = new JournalTrade();
    j.setMode(JournalTrade.Mode.valueOf(req.getMode()));
    j.setStockCode(req.getStockCode());
    j.setStockName(req.getStockName());
    j.setEntryPrice(req.getEntryPrice());
    j.setEntryDate(req.getEntryDate() != null ? req.getEntryDate() : LocalDateTime.now());
    j.setEntryShares(req.getEntryShares());
    j.setAccountAtEntry(req.getAccountAtEntry());
    j.setRiskPercent(req.getRiskPercent());
    j.setStopPrice(req.getStopPrice());
    j.setTargetPrice(req.getTargetPrice());
    j.setInitialRisk(
        req.getEntryPrice().subtract(req.getStopPrice()).setScale(2, RoundingMode.HALF_UP));
    j.setIsOpen(1);
    j.setTags(req.getTags());
    j.setSetupNotes(req.getSetupNotes());
    j.setSource("MANUAL");
    j.setCreatedBy(username);
    return JournalTradeDTO.from(repo.save(j));
  }

  @Transactional
  public JournalTradeDTO update(Long id, JournalTradeUpdateRequest req) {
    JournalTrade j =
        repo.findActiveById(id)
            .orElseThrow(() -> new IllegalArgumentException("trade 不存在或已删除: " + id));

    if (req.getStopPrice() != null) {
      if (req.getStopPrice().compareTo(j.getStopPrice()) < 0) {
        throw new IllegalArgumentException(
            "止损只能收紧,不能放松(纪律红线) — 当前 " + j.getStopPrice() + ",新值 " + req.getStopPrice());
      }
      j.setStopPrice(req.getStopPrice());
      // Recompute initial_risk if entry is unchanged
      j.setInitialRisk(
          j.getEntryPrice().subtract(req.getStopPrice()).setScale(2, RoundingMode.HALF_UP));
    }
    if (req.getTargetPrice() != null) j.setTargetPrice(req.getTargetPrice());
    if (req.getTags() != null) j.setTags(req.getTags());
    if (req.getSetupNotes() != null) j.setSetupNotes(req.getSetupNotes());
    if (req.getReviewNotes() != null) j.setReviewNotes(req.getReviewNotes());

    // Closing the trade
    if (req.getExitPrice() != null) {
      if (j.getIsOpen() != 1) {
        throw new IllegalArgumentException("该 trade 已平仓,不能再设 exitPrice");
      }
      j.setExitPrice(req.getExitPrice());
      j.setExitDate(req.getExitDate() != null ? req.getExitDate() : LocalDateTime.now());
      if (req.getExitReason() != null) {
        try {
          j.setExitReason(JournalTrade.ExitReason.valueOf(req.getExitReason()));
        } catch (IllegalArgumentException e) {
          throw new IllegalArgumentException("exitReason 非法: " + req.getExitReason());
        }
      } else {
        j.setExitReason(JournalTrade.ExitReason.manual);
      }
      BigDecimal pnl =
          req.getExitPrice()
              .subtract(j.getEntryPrice())
              .multiply(new BigDecimal(j.getEntryShares()))
              .setScale(2, RoundingMode.HALF_UP);
      j.setPnlAmount(pnl);
      BigDecimal totalRisk = j.getInitialRisk().multiply(new BigDecimal(j.getEntryShares()));
      if (totalRisk.signum() == 0) {
        throw new IllegalArgumentException("initialRisk * shares = 0,无法算 R");
      }
      j.setRMultiple(pnl.divide(totalRisk, 4, RoundingMode.HALF_UP));
      j.setIsOpen(0);
    }
    return JournalTradeDTO.from(repo.save(j));
  }

  @Transactional
  public void softDelete(Long id) {
    JournalTrade j =
        repo.findActiveById(id).orElseThrow(() -> new IllegalArgumentException("trade 不存在: " + id));
    j.setIsDeleted(1);
    repo.save(j);
  }

  public JournalTradeDTO findOne(Long id) {
    return JournalTradeDTO.from(
        repo.findActiveById(id)
            .orElseThrow(() -> new IllegalArgumentException("trade 不存在: " + id)));
  }

  @Transactional(readOnly = true)
  public Page<JournalTradeDTO> list(
      String mode,
      Boolean isOpen,
      String tag,
      java.time.LocalDate from,
      java.time.LocalDate to,
      Pageable pageable) {
    Specification<JournalTrade> spec =
        (root, q, cb) -> {
          List<Predicate> ps = new ArrayList<>();
          ps.add(cb.equal(root.get("isDeleted"), 0));
          if (mode != null && !mode.isBlank()) {
            ps.add(cb.equal(root.get("mode"), JournalTrade.Mode.valueOf(mode)));
          }
          if (isOpen != null) {
            ps.add(cb.equal(root.get("isOpen"), isOpen ? 1 : 0));
          }
          if (from != null) {
            ps.add(cb.greaterThanOrEqualTo(root.get("entryDate"), from.atStartOfDay()));
          }
          if (to != null) {
            ps.add(cb.lessThan(root.get("entryDate"), to.plusDays(1).atStartOfDay()));
          }
          if (tag != null && !tag.isBlank()) {
            ps.add(cb.like(root.get("tags"), "%" + tag + "%"));
          }
          return cb.and(ps.toArray(new Predicate[0]));
        };
    return repo.findAll(spec, pageable).map(JournalTradeDTO::from);
  }

  @Transactional(readOnly = true)
  public List<JournalTradeDTO> listOpen() {
    return repo.findAllOpen().stream().map(JournalTradeDTO::from).toList();
  }

  @Transactional(readOnly = true)
  public List<PendingFillDTO> pendingFills() {
    var since = LocalDateTime.now().minusDays(30);
    var fills = fillRepo.findRecentSince(since);
    return fills.stream()
        .filter(f -> "clear".equalsIgnoreCase(f.getAction()))
        .filter(f -> repo.findBySourceRef(f.getId()).isEmpty())
        .map(
            f ->
                PendingFillDTO.builder()
                    .fillId(f.getId())
                    .stockCode(f.getStockCode())
                    // poolType not on entity; derive from poolId via lookup if needed
                    .action(f.getAction())
                    .price(f.getPrice())
                    .lots(f.getLots())
                    .filledAt(f.getFilledAt())
                    .note(f.getNote())
                    .build())
        .toList();
  }

  /**
   * Sync a single fill from invest_position_fill into journal_trade.
   *
   * <p>Direct entity manipulation (not service.create + service.update) because: - We're recording
   * history, not enforcing new-trade discipline - The entry price/stop are derived from existing
   * pool data - Avoids any double-transaction bookkeeping
   */
  @Transactional
  public JournalTradeDTO syncFromFill(Long fillId, String username) {
    InvestPositionFill fill =
        fillRepo
            .findById(fillId)
            .orElseThrow(() -> new IllegalArgumentException("fill 不存在: " + fillId));
    if (repo.findBySourceRef(fillId).isPresent()) {
      throw new IllegalStateException("该 fill 已同步过(重复同步)");
    }
    // avgCost not on entity; use price as entry
    BigDecimal entry = fill.getPrice();
    BigDecimal stop = entry.multiply(new BigDecimal("0.95")).setScale(2, RoundingMode.HALF_UP);

    JournalTrade j = new JournalTrade();
    j.setMode(JournalTrade.Mode.REAL);
    j.setSource("POOL_SYNC");
    j.setSourceRefId(fillId);
    j.setStockCode(fill.getStockCode());
    // stockName not on entity; leave null
    j.setEntryPrice(entry);
    j.setStopPrice(stop);
    j.setTargetPrice(null);
    j.setEntryShares(
        fill.getLots() != null ? fill.getLots().multiply(new BigDecimal("100")).intValue() : 0);
    j.setEntryDate(fill.getFilledAt());
    j.setInitialRisk(entry.subtract(stop).setScale(2, RoundingMode.HALF_UP));
    j.setIsOpen(1);
    j.setSetupNotes("POOL_SYNC from fillId=" + fillId);
    j.setCreatedBy(username);

    if ("clear".equalsIgnoreCase(fill.getAction())) {
      BigDecimal pnl =
          fill.getPrice()
              .subtract(entry)
              .multiply(new BigDecimal(j.getEntryShares()))
              .setScale(2, RoundingMode.HALF_UP);
      j.setExitPrice(fill.getPrice());
      j.setExitDate(fill.getFilledAt());
      j.setExitReason(JournalTrade.ExitReason.manual);
      j.setPnlAmount(pnl);
      BigDecimal totalRisk = j.getInitialRisk().multiply(new BigDecimal(j.getEntryShares()));
      if (totalRisk.signum() > 0) {
        j.setRMultiple(pnl.divide(totalRisk, 4, RoundingMode.HALF_UP));
      }
      j.setIsOpen(0);
      j.setReviewNotes("从投资池同步的清仓记录(fillId=" + fillId + ")");
    }
    return JournalTradeDTO.from(repo.save(j));
  }

  private void validate(JournalTradeCreateRequest req) {
    if (req.getMode() == null
        || (!req.getMode().equals("REAL") && !req.getMode().equals("PAPER"))) {
      throw new IllegalArgumentException("mode 必须为 REAL 或 PAPER");
    }
    if (req.getStockCode() == null || req.getStockCode().isBlank()) {
      throw new IllegalArgumentException("stockCode 必填");
    }
    if (req.getEntryPrice() == null || req.getEntryPrice().signum() <= 0) {
      throw new IllegalArgumentException("entryPrice 必须 > 0");
    }
    if (req.getStopPrice() == null || req.getStopPrice().signum() <= 0) {
      throw new IllegalArgumentException("stopPrice 必须 > 0");
    }
    if (req.getEntryPrice().compareTo(req.getStopPrice()) <= 0) {
      throw new IllegalArgumentException("entryPrice 必须 > stopPrice");
    }
    if (req.getEntryShares() == null || req.getEntryShares() <= 0) {
      throw new IllegalArgumentException("entryShares 必须 > 0");
    }
    if (req.getTargetPrice() != null) {
      BigDecimal reward = req.getTargetPrice().subtract(req.getEntryPrice());
      BigDecimal risk = req.getEntryPrice().subtract(req.getStopPrice());
      if (reward.compareTo(risk.multiply(new BigDecimal("3"))) < 0) {
        throw new IllegalArgumentException("风险回报比 < 1:3,违反红线 — Minervini 不会进场");
      }
    }
    if (req.getRiskPercent() != null
        && req.getRiskPercent().compareTo(new BigDecimal("0.02")) > 0) {
      throw new IllegalArgumentException(
          "单笔风险 " + req.getRiskPercent().multiply(new BigDecimal("100")) + "% 超过 2% 红线");
    }
  }
}
