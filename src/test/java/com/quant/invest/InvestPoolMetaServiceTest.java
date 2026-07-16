package com.quant.invest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.quant.dto.invest.PoolMetaDTO;
import com.quant.dto.invest.PoolMetaUpdateRequest;
import com.quant.entity.InvestPoolMeta;
import com.quant.repository.InvestPoolMetaRepository;
import com.quant.service.InvestPoolMetaService;

/**
 * 股票池元信息（封面图 / 估值方法 / 每周机会点）服务单测。
 *
 * <p>关键不变量： 1. get(poolType) 表为空时返回 null（404 由 controller 处理） 2. toDto 必须把 MD 原文（valuationMethodMd
 * / weeklyOpportunityMd）一并带上， 否则编辑弹窗无法回显原始 markdown。这是历史 bug 的回归点： DTO 起初只输出 HTML 字段，前端拿不到 MD →
 * textarea 一直为空。 3. update() 仅在请求字段非 null 时覆盖（保持其它字段不动），并把 MD → HTML 重渲染 4. update() 拒绝 poolType ∉
 * {tech_ai, innovative_drug, quality}
 */
@DisplayName("InvestPoolMetaService")
class InvestPoolMetaServiceTest {

  private final InvestPoolMetaRepository repo = mock(InvestPoolMetaRepository.class);

  private final InvestPoolMetaService service = new InvestPoolMetaService(repo);

  // ══════════════════════════════════════════════════
  // toDto() — 回归：MD 原文必须随 DTO 一起返回
  // ══════════════════════════════════════════════════

  @Test
  @DisplayName("get：DTO 必须携带 MD 原文（编辑弹窗回显用）")
  void getExposesMarkdownSourceFields() {
    InvestPoolMeta meta = new InvestPoolMeta();
    meta.setPoolType("tech_ai");
    meta.setDisplayName("科技 AI");
    meta.setCoverImageUrl("/uploads/test.png");
    meta.setValuationMethodMd("### 10 倍 PS\n\n合理市值 = 预测营收 × 10");
    meta.setValuationMethodHtml("<h3>10 倍 PS</h3><p>合理市值 = 预测营收 × 10</p>");
    meta.setWeeklyOpportunityMd("- 行业：CXO\n  催化：...");
    meta.setWeeklyOpportunityHtml("<ul><li>行业：CXO...</li></ul>");
    meta.setDisplayOrder(1);
    when(repo.findById("tech_ai")).thenReturn(java.util.Optional.of(meta));

    PoolMetaDTO dto = service.get("tech_ai");

    assertThat(dto).isNotNull();
    assertThat(dto.getValuationMethodMd()).isEqualTo("### 10 倍 PS\n\n合理市值 = 预测营收 × 10");
    assertThat(dto.getWeeklyOpportunityMd()).isEqualTo("- 行业：CXO\n  催化：...");
    // HTML 同时也要带上（页面渲染用）
    assertThat(dto.getValuationMethodHtml()).contains("<h3>");
    assertThat(dto.getWeeklyOpportunityHtml()).contains("<ul>");
  }

  @Test
  @DisplayName("get：表里没有这条 → 返回 null（不让前端拿到空 DTO 触发 200 + 空对象）")
  void getReturnsNullWhenAbsent() {
    when(repo.findById("ghost")).thenReturn(java.util.Optional.empty());

    PoolMetaDTO dto = service.get("ghost");

    assertThat(dto).isNull();
  }

  @Test
  @DisplayName("DTO 类本身必须声明 MD 字段（防止有人删 DTO 字段再次引入回归）")
  void dtoClassDeclaresMarkdownFields() {
    assertThat(PoolMetaDTO.class.getDeclaredFields())
        .extracting("name")
        .contains("valuationMethodMd", "weeklyOpportunityMd");
  }

  // ══════════════════════════════════════════════════
  // update() — 部分更新 + 渲染
  // ══════════════════════════════════════════════════

  @Test
  @DisplayName("update：只传 valuationMethodMd → 只重渲估值方法，不动每周机会点")
  void updateOnlyTouchesSpecifiedFields() {
    InvestPoolMeta existing = new InvestPoolMeta();
    existing.setPoolType("tech_ai");
    existing.setDisplayName("科技 AI");
    existing.setValuationMethodMd("old");
    existing.setValuationMethodHtml("<p>old</p>");
    existing.setWeeklyOpportunityMd("keep me");
    existing.setWeeklyOpportunityHtml("<p>keep me</p>");
    existing.setDisplayOrder(1);
    when(repo.findById("tech_ai")).thenReturn(java.util.Optional.of(existing));
    when(repo.save(any(InvestPoolMeta.class))).thenAnswer(inv -> inv.getArgument(0));

    PoolMetaUpdateRequest req = new PoolMetaUpdateRequest();
    req.setValuationMethodMd("### 12 倍 PS");

    PoolMetaDTO dto = service.update("tech_ai", req);

    assertThat(dto.getValuationMethodMd()).isEqualTo("### 12 倍 PS");
    // markdown 渲染成 HTML（带 h3）
    assertThat(dto.getValuationMethodHtml()).contains("<h3>");
    // 每周机会点保持原状
    assertThat(dto.getWeeklyOpportunityMd()).isEqualTo("keep me");
    assertThat(dto.getWeeklyOpportunityHtml()).isEqualTo("<p>keep me</p>");
    // displayName / displayOrder 没动
    assertThat(dto.getDisplayName()).isEqualTo("科技 AI");
  }

  @Test
  @DisplayName("update：poolType 不合法 → 抛 IllegalArgumentException，不动库")
  void updateRejectsInvalidPoolType() {
    PoolMetaUpdateRequest req = new PoolMetaUpdateRequest();
    req.setValuationMethodMd("x");

    try {
      service.update("invalid_type", req);
      org.junit.jupiter.api.Assertions.fail("应抛 IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).contains("invalid_type");
    }

    // 因为根本没走到 findById
    org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).findById(any());
    org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).save(any());
  }

  @Test
  @DisplayName("update：把保存后的对象交给 save()（让 JPA 写回 DB）")
  void updateReturnsSavedEntity() {
    InvestPoolMeta existing = new InvestPoolMeta();
    existing.setPoolType("innovative_drug");
    existing.setDisplayName("创新药");
    existing.setValuationMethodMd("a");
    existing.setWeeklyOpportunityMd("b");
    existing.setDisplayOrder(2);
    when(repo.findById("innovative_drug")).thenReturn(java.util.Optional.of(existing));
    when(repo.save(any(InvestPoolMeta.class))).thenAnswer(inv -> inv.getArgument(0));

    PoolMetaUpdateRequest req = new PoolMetaUpdateRequest();
    req.setValuationMethodMd("updated");
    service.update("innovative_drug", req);

    ArgumentCaptor<InvestPoolMeta> captor = ArgumentCaptor.forClass(InvestPoolMeta.class);
    org.mockito.Mockito.verify(repo).save(captor.capture());
    assertThat(captor.getValue().getValuationMethodMd()).isEqualTo("updated");
    // displayOrder 保留
    assertThat(captor.getValue().getDisplayOrder()).isEqualTo(2);
  }
}
