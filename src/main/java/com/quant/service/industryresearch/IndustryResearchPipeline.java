package com.quant.service.industryresearch;

import com.quant.dto.industryresearch.PipelineRunResultDTO;
import com.quant.entity.IndustryResearchArticle;
import com.quant.entity.IndustryResearchCategory;
import com.quant.entity.IndustryResearchTask;
import com.quant.repository.IndustryResearchArticleRepository;
import com.quant.repository.IndustryResearchCategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 产业投研流水线（异步编排）
 *
 * 流程：
 *   1) A-Stock-Data 取数据（行情 + 板块 + 资金流）
 *   2) Kimi CLI 读研报（批量阅读 1171 篇，节省 Claude token）
 *   3) News Radar 抓 24h 新闻
 *   4) IndustryReportAssembler 组装 11 Tab JSON
 *   5) 写入 industry_research_article + industry_research_section
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndustryResearchPipeline {

    private final IndustryResearchService researchService;
    private final IndustryDataFetcher dataFetcher;
    private final IndustryReportReader reportReader;
    private final NewsRadarService newsRadar;
    private final IndustryReportAssembler assembler;
    private final IndustryResearchCategoryRepository categoryRepo;
    private final IndustryResearchArticleRepository articleRepo;

    /** 用 ApplicationContext 拿代理，绕过 @Async self-invocation 不生效问题 */
    @Autowired
    private ApplicationContext applicationContext;

    private IndustryResearchPipeline self() {
        return applicationContext.getBean(IndustryResearchPipeline.class);
    }

    /**
     * 创建任务 + 异步执行
     */
    @Transactional
    public IndustryResearchTask createAndRun(String categoryCode, String keyword, String taskName) {
        IndustryResearchCategory cat = categoryRepo.findByCode(categoryCode)
                .orElseThrow(() -> new IllegalArgumentException("产业不存在: " + categoryCode));

        IndustryResearchTask task = new IndustryResearchTask();
        task.setCategoryId(cat.getId());
        task.setTaskName(String.format("%s · %s",
                taskName != null ? taskName : cat.getName() + " 投研",
                LocalDate.now()));
        task.setKeyword(keyword != null ? keyword : cat.getName());
        task.setStatus("pending");
        task.setStage("init");
        task.setProgress(0);
        task.setLog("任务创建，等待执行...\n");
        IndustryResearchTask saved = researchService.saveTask(task);

        // 异步执行（通过 ApplicationContext 拿代理，绕过 self-invocation）
        self().runAsync(saved.getId(), categoryCode, keyword != null ? keyword : cat.getName());
        return saved;
    }

    /**
     * 异步执行（Spring @Async）
     */
    @Async
    public void runAsync(Long taskId, String categoryCode, String keyword) {
        log.info("[Pipeline-{}] 开始执行 keyword={}", taskId, keyword);
        IndustryResearchTask task = researchService.findTask(taskId).orElse(null);
        if (task == null) return;

        List<String> stageLog = new ArrayList<>();
        try {
            task.setStartedAt(LocalDateTime.now());
            task.setStatus("running");

            /* ============ 阶段 1: A-Stock-Data 取数据 ============ */
            updateStage(taskId, "data-fetch", 10, stageLog, "阶段 1: A-Stock-Data 取数据...");
            Map<String, Object> dataResult = dataFetcher.fetchSectorFlow(keyword);
            stageLog.add("  ✓ 板块资金流已抓取");
            updateProgress(taskId, 25, stageLog);

            /* ============ 阶段 2: Kimi CLI 读研报 ============ */
            updateStage(taskId, "report-read", 30, stageLog, "阶段 2: Kimi CLI 读研报...");
            Map<String, Object> digest = reportReader.readAndDigest(keyword, 1500);
            Integer totalRead = (Integer) digest.getOrDefault("totalRead", 0);
            task.setTotalReports(totalRead);
            stageLog.add("  ✓ Kimi 读取 " + totalRead + " 篇研报，提炼完成");
            updateProgress(taskId, 60, stageLog);

            /* ============ 阶段 3: News Radar 抓新闻 ============ */
            updateStage(taskId, "news-radar", 65, stageLog, "阶段 3: News Radar 抓 24h 新闻...");
            Map<String, Object> news = newsRadar.fetch24h(keyword);
            Integer newsCount = (Integer) news.getOrDefault("newsCount", 0);
            task.setNewsCount(newsCount);
            stageLog.add("  ✓ News Radar 抓到 " + newsCount + " 条新闻");
            updateProgress(taskId, 80, stageLog);

            /* ============ 阶段 4: 组装 11 Tab 报告 ============ */
            updateStage(taskId, "assembling", 85, stageLog, "阶段 4: 组装 11 Tab 结构化报告...");
            List<Map<String, Object>> sections = assembler.assemble(keyword, dataResult, digest, news);
            stageLog.add("  ✓ 11 个 Tab 已组装完成");

            /* ============ 阶段 5: 写库 ============ */
            updateStage(taskId, "saving", 92, stageLog, "阶段 5: 写入数据库...");

            IndustryResearchCategory cat = categoryRepo.findByCode(categoryCode).orElseThrow();
            IndustryResearchArticle article = new IndustryResearchArticle();
            article.setCategoryId(cat.getId());
            article.setSlug(cat.getCode() + "-" + LocalDate.now());
            article.setTitle(cat.getName() + " 产业链深度分析");
            article.setSubtitle("AI 自动化投研 · " + LocalDate.now() + " · 数据来源：Kimi " + totalRead + " 篇研报 + News Radar " + newsCount + " 条");
            article.setStatus("published");
            article.setUpdateDate(LocalDate.now());
            article.setSourceSummary("Kimi CLI " + totalRead + " 篇研报 + News Radar " + newsCount + " 条 + A-Stock-Data 实时行情");
            article.setTags(cat.getName() + ",AI 投研,自动化," + LocalDate.now());

            researchService.upsertArticle(article, sections);
            task.setArticleId(article.getId());
            stageLog.add("  ✓ 文章已写入 articleId=" + article.getId());

            /* ============ 完成 ============ */
            task.setStatus("success");
            task.setStage("done");
            task.setProgress(100);
            task.setFinishedAt(LocalDateTime.now());
            task.setLog(String.join("\n", stageLog));
            researchService.saveTask(task);
            log.info("[Pipeline-{}] 执行成功 articleId={}", taskId, article.getId());

        } catch (Exception e) {
            log.error("[Pipeline-{}] 执行失败: {}", taskId, e.getMessage(), e);
            task.setStatus("failed");
            task.setErrorMessage(e.getMessage());
            task.setFinishedAt(LocalDateTime.now());
            stageLog.add("  ✗ 失败: " + e.getMessage());
            task.setLog(String.join("\n", stageLog));
            researchService.saveTask(task);
        }
    }

    private void updateStage(Long taskId, String stage, int progress, List<String> log, String msg) {
        researchService.findTask(taskId).ifPresent(t -> {
            t.setStage(stage);
            t.setProgress(progress);
            log.add(msg);
            t.setLog(String.join("\n", log));
            researchService.saveTask(t);
        });
    }

    private void updateProgress(Long taskId, int progress, List<String> log) {
        researchService.findTask(taskId).ifPresent(t -> {
            t.setProgress(progress);
            t.setLog(String.join("\n", log));
            researchService.saveTask(t);
        });
    }
}