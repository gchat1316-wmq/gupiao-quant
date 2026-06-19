package com.quant.service.industryresearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.dto.industryresearch.*;
import com.quant.entity.*;
import com.quant.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 产业投研主服务：菜单 / 文章 / 章节 CRUD
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndustryResearchService {

    private final ObjectMapper mapper = new ObjectMapper();
    private final IndustryResearchCategoryRepository categoryRepo;
    private final IndustryResearchArticleRepository articleRepo;
    private final IndustryResearchSectionRepository sectionRepo;
    private final IndustryResearchTaskRepository taskRepo;

    /* ============ 菜单 ============ */
    public List<CategoryDTO> listCategories() {
        List<IndustryResearchCategory> cats = categoryRepo.findByEnabledOrderBySortOrderAsc(1);
        Map<Long, Long> articleCount = articleRepo.findAllPublished().stream()
                .collect(Collectors.groupingBy(IndustryResearchArticle::getCategoryId, Collectors.counting()));
        return cats.stream().map(c -> new CategoryDTO(
                c.getId(), c.getCode(), c.getName(), c.getIcon(), c.getSortOrder(),
                articleCount.getOrDefault(c.getId(), 0L).intValue(),
                c.getDescription()
        )).collect(Collectors.toList());
    }

    public Optional<IndustryResearchCategory> findCategoryByCode(String code) {
        return categoryRepo.findByCode(code);
    }

    /* ============ 文章列表 ============ */
    public List<ArticleSummaryDTO> listArticles(Long categoryId) {
        List<IndustryResearchArticle> list = categoryId == null
                ? articleRepo.findAllPublished()
                : articleRepo.findPublishedByCategory(categoryId);
        return list.stream().map(this::toSummary).collect(Collectors.toList());
    }

    /* ============ 文章详情（含 11 Tab） ============ */
    @Transactional
    public Optional<ArticleDetailDTO> getArticleDetail(Long id) {
        return articleRepo.findById(id).map(a -> {
            // viewCount +1
            a.setViewCount(a.getViewCount() + 1);
            articleRepo.save(a);

            List<IndustryResearchSection> sections = sectionRepo.findByArticleIdOrderBySectionOrderAsc(id);
            List<SectionDTO> sectionDTOs = sections.stream().map(this::toSection).collect(Collectors.toList());
            return new ArticleDetailDTO(toSummary(a), sectionDTOs);
        });
    }

    public Optional<ArticleDetailDTO> getArticleDetailBySlug(String slug) {
        return articleRepo.findBySlug(slug).flatMap(a -> getArticleDetail(a.getId()));
    }

    /* ============ 创建 / 更新文章 + 章节 ============ */
    @Transactional
    public IndustryResearchArticle upsertArticle(IndustryResearchArticle article,
                                                List<Map<String, Object>> sectionPayloads) {
        if (article.getId() == null && !StringUtils.hasText(article.getSlug())) {
            article.setSlug(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        }
        article.setStatus(StringUtils.hasText(article.getStatus()) ? article.getStatus() : "draft");
        article.setVersion(article.getVersion() == null ? 1 : article.getVersion() + 1);
        IndustryResearchArticle saved = articleRepo.save(article);

        if (sectionPayloads != null) {
            sectionRepo.deleteByArticleId(saved.getId());
            int order = 1;
            for (Map<String, Object> p : sectionPayloads) {
                IndustryResearchSection s = new IndustryResearchSection();
                s.setArticleId(saved.getId());
                s.setSectionKey(String.valueOf(p.get("sectionKey")));
                s.setSectionTitle(String.valueOf(p.get("sectionTitle")));
                s.setSectionOrder(order++);
                s.setContentType(String.valueOf(p.getOrDefault("contentType", "mixed")));
                try {
                    s.setContentJson(mapper.writeValueAsString(p.get("content")));
                } catch (Exception e) {
                    log.warn("section content 序列化失败: {}", e.getMessage());
                    s.setContentJson("{}");
                }
                s.setSource((String) p.get("source"));
                sectionRepo.save(s);
            }
        }
        return saved;
    }

    /* ============ 任务列表 ============ */
    public List<TaskDTO> listTasks(Long categoryId) {
        List<IndustryResearchTask> tasks = categoryId == null
                ? taskRepo.findTop20ByOrderByCreatedAtDesc()
                : taskRepo.findByCategoryIdOrderByCreatedAtDesc(categoryId);
        Map<Long, IndustryResearchCategory> catMap = categoryRepo.findAll().stream()
                .collect(Collectors.toMap(IndustryResearchCategory::getId, c -> c));
        return tasks.stream().map(t -> toTaskDTO(t, catMap)).collect(Collectors.toList());
    }

    public Optional<IndustryResearchTask> findTask(Long id) {
        return taskRepo.findById(id);
    }

    @Transactional
    public IndustryResearchTask saveTask(IndustryResearchTask task) {
        return taskRepo.save(task);
    }

    /* ============ Mapper ============ */
    private ArticleSummaryDTO toSummary(IndustryResearchArticle a) {
        IndustryResearchCategory cat = categoryRepo.findById(a.getCategoryId()).orElse(null);
        return new ArticleSummaryDTO(
                a.getId(), a.getCategoryId(),
                cat != null ? cat.getCode() : null,
                cat != null ? cat.getName() : null,
                a.getSlug(), a.getTitle(), a.getSubtitle(), a.getStatus(), a.getVersion(),
                a.getUpdateDate(), a.getSourceSummary(), a.getTags(), a.getViewCount(),
                a.getCreatedAt(), a.getUpdatedAt()
        );
    }

    private SectionDTO toSection(IndustryResearchSection s) {
        JsonNode node;
        try {
            node = mapper.readTree(s.getContentJson());
        } catch (Exception e) {
            node = mapper.createObjectNode();
        }
        return new SectionDTO(s.getId(), s.getSectionKey(), s.getSectionTitle(),
                s.getSectionOrder(), s.getContentType(), node, s.getSource());
    }

    private TaskDTO toTaskDTO(IndustryResearchTask t, Map<Long, IndustryResearchCategory> catMap) {
        IndustryResearchCategory cat = catMap.get(t.getCategoryId());
        return new TaskDTO(
                t.getId(), t.getCategoryId(),
                cat != null ? cat.getCode() : null,
                cat != null ? cat.getName() : null,
                t.getArticleId(), t.getTaskName(), t.getKeyword(),
                t.getStatus(), t.getStage(), t.getProgress(),
                t.getTotalReports(), t.getNewsCount(),
                t.getErrorMessage(), t.getLog(),
                t.getStartedAt(), t.getFinishedAt(), t.getCreatedAt()
        );
    }
}