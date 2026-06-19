package com.quant.controller;

import com.quant.dto.industryresearch.*;
import com.quant.entity.IndustryResearchArticle;
import com.quant.entity.IndustryResearchCategory;
import com.quant.entity.IndustryResearchTask;
import com.quant.repository.IndustryResearchArticleRepository;
import com.quant.repository.IndustryResearchCategoryRepository;
import com.quant.service.industryresearch.IndustryResearchPipeline;
import com.quant.service.industryresearch.IndustryResearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 产业投研 REST API
 */
@RestController
@RequestMapping("/api/industry-research")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class IndustryResearchController {

    private final IndustryResearchService researchService;
    private final IndustryResearchPipeline pipeline;
    private final IndustryResearchCategoryRepository categoryRepo;
    private final IndustryResearchArticleRepository articleRepo;

    /* ============ 菜单 / 分类 ============ */
    @GetMapping("/categories")
    public List<CategoryDTO> listCategories() {
        return researchService.listCategories();
    }

    /* ============ 文章 ============ */
    @GetMapping("/articles")
    public List<ArticleSummaryDTO> listArticles(@RequestParam(value = "categoryId", required = false) Long categoryId) {
        return researchService.listArticles(categoryId);
    }

    @GetMapping("/article/{id}")
    public ResponseEntity<ArticleDetailDTO> getArticle(@PathVariable Long id) {
        return researchService.getArticleDetail(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/article/slug/{slug}")
    public ResponseEntity<ArticleDetailDTO> getArticleBySlug(@PathVariable String slug) {
        return researchService.getArticleDetailBySlug(slug)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /* ============ 任务 ============ */
    @GetMapping("/tasks")
    public List<TaskDTO> listTasks(@RequestParam(value = "categoryId", required = false) Long categoryId) {
        return researchService.listTasks(categoryId);
    }

    @GetMapping("/task/{id}")
    public ResponseEntity<TaskDTO> getTask(@PathVariable Long id) {
        return researchService.findTask(id)
                .map(t -> {
                    IndustryResearchCategory cat = categoryRepo.findById(t.getCategoryId()).orElse(null);
                    return new TaskDTO(
                            t.getId(), t.getCategoryId(),
                            cat != null ? cat.getCode() : null,
                            cat != null ? cat.getName() : null,
                            t.getArticleId(), t.getTaskName(), t.getKeyword(),
                            t.getStatus(), t.getStage(), t.getProgress(),
                            t.getTotalReports(), t.getNewsCount(),
                            t.getErrorMessage(), t.getLog(),
                            t.getStartedAt(), t.getFinishedAt(), t.getCreatedAt());
                })
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /* ============ 触发流水线 ============ */
    @PostMapping("/pipeline/run")
    public ResponseEntity<TaskDTO> triggerPipeline(@RequestBody PipelineTriggerRequest req) {
        IndustryResearchTask task = pipeline.createAndRun(req.categoryCode(), req.keyword(), req.taskName());
        return getTask(task.getId());
    }

    /* ============ 健康检查 / 数据源诊断 ============ */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new HashMap<>();
        out.put("status", "ok");
        out.put("articleCount", articleRepo.count());
        out.put("categoryCount", categoryRepo.count());
        out.put("stages", List.of("data-fetch", "report-read", "news-radar", "assembling", "done"));
        return out;
    }
}