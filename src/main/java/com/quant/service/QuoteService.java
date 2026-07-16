package com.quant.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.quant.config.AiProperties;
import com.quant.dto.QuoteDTO;
import com.quant.dto.QuotePageDTO;
import com.quant.entity.InvestQuote;
import com.quant.entity.StudyKnowledgeNode;
import com.quant.entity.StudyQuiz;
import com.quant.repository.InvestQuoteRepository;
import com.quant.repository.StudyKnowledgeNodeRepository;
import com.quant.repository.StudyQuizRepository;
import com.quant.service.ai.AiKnowledgeExtractionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuoteService {

  private static final Long QUOTE_COURSE_ID = 108L;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final InvestQuoteRepository quoteRepo;
  private final StudyKnowledgeNodeRepository nodeRepo;
  private final StudyQuizRepository quizRepo;
  private final AiKnowledgeExtractionService aiService;
  private final AiProperties aiProperties;

  public QuotePageDTO search(String kw, int page, int pageSize) {
    Page<InvestQuote> result =
        quoteRepo.search(
            (kw == null || kw.isBlank()) ? "" : kw.trim(), PageRequest.of(page, pageSize));
    return QuotePageDTO.builder()
        .list(result.getContent().stream().map(this::toDTO).collect(Collectors.toList()))
        .total(result.getTotalElements())
        .page(page)
        .pageSize(pageSize)
        .build();
  }

  public QuoteDTO create(QuoteDTO req) {
    InvestQuote q = new InvestQuote();
    q.setContent(req.getContent());
    q.setAuthor(req.getAuthor());
    q.setSource(req.getSource());
    q.setTags(req.getTags());
    q.setLikes(0);
    return toDTO(quoteRepo.save(q));
  }

  public List<QuoteDTO> batchCreate(List<String> lines) {
    return lines.stream()
        .map(String::trim)
        .filter(s -> !s.isBlank())
        .map(
            line -> {
              InvestQuote q = new InvestQuote();
              q.setContent(line);
              q.setLikes(0);
              return toDTO(quoteRepo.save(q));
            })
        .collect(Collectors.toList());
  }

  public void like(Long id) {
    quoteRepo.incrementLikes(id);
  }

  public void delete(Long id) {
    if (!quoteRepo.existsById(id)) {
      throw new IllegalArgumentException("金句不存在: " + id);
    }
    quoteRepo.deleteById(id);
  }

  /** 将金句导入为知识节点，并 AI 生成测验题。 返回新建的 nodeId。 */
  public Long importToStudy(Long id) {
    InvestQuote quote =
        quoteRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("金句不存在: " + id));

    if (quote.getImportedNodeId() != null) {
      return quote.getImportedNodeId();
    }

    // 创建知识节点
    StudyKnowledgeNode node = new StudyKnowledgeNode();
    node.setCourseId(QUOTE_COURSE_ID);
    node.setParentId(null);
    String title =
        quote.getContent().length() > 30
            ? quote.getContent().substring(0, 30) + "…"
            : quote.getContent();
    if (quote.getAuthor() != null && !quote.getAuthor().isBlank()) {
      title = "【" + quote.getAuthor() + "】" + title;
    }
    node.setTitle(title);
    node.setSummary(quote.getContent());
    node.setDefinition(
        quote.getContent()
            + (quote.getSource() != null ? "\n——" + quote.getSource() : "")
            + (quote.getAuthor() != null ? " ·" + quote.getAuthor() : ""));
    node.setLevel(1);
    node.setSort(0);
    node.setMastered(0);
    StudyKnowledgeNode saved = nodeRepo.save(node);

    // AI 生成测验题
    try {
      generateQuiz(saved, quote.getContent());
    } catch (Exception e) {
      log.warn("金句测验题生成失败，跳过: {}", e.getMessage());
    }

    quoteRepo.setImportedNodeId(id, saved.getId());
    return saved.getId();
  }

  private void generateQuiz(StudyKnowledgeNode node, String quoteContent) throws Exception {
    String systemPrompt =
        "你是一位投资学习助手，请根据投资金句生成一道单选题来检验理解。"
            + "输出纯 JSON，格式：{\"stem\":\"题干\",\"options\":[{\"key\":\"A\",\"text\":\"...\"},{\"key\":\"B\",\"text\":\"...\"},{\"key\":\"C\",\"text\":\"...\"},{\"key\":\"D\",\"text\":\"...\"}],\"answer\":\"A\",\"analysis\":\"解析\"}";
    String userPrompt = "投资金句：" + quoteContent + "\n\n请出一道考察对该金句理解的单选题，4个选项，只有1个正确答案。";

    String raw = aiService.extractTextOnly(systemPrompt, userPrompt);

    // 提取 JSON（可能被 markdown 包裹）
    String json = raw.trim();
    int start = json.indexOf('{');
    int end = json.lastIndexOf('}');
    if (start >= 0 && end > start) {
      json = json.substring(start, end + 1);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> parsed = MAPPER.readValue(json, Map.class);
    StudyQuiz quiz = new StudyQuiz();
    quiz.setNodeId(node.getId());
    quiz.setStem((String) parsed.get("stem"));
    quiz.setOptionsJson(MAPPER.writeValueAsString(parsed.get("options")));
    quiz.setAnswer((String) parsed.get("answer"));
    quiz.setAnalysis((String) parsed.get("analysis"));
    quiz.setSort(0);
    quizRepo.save(quiz);
    log.info("金句测验题生成完成: nodeId={}", node.getId());
  }

  private QuoteDTO toDTO(InvestQuote q) {
    return QuoteDTO.builder()
        .id(q.getId())
        .content(q.getContent())
        .author(q.getAuthor())
        .source(q.getSource())
        .tags(q.getTags())
        .likes(q.getLikes())
        .importedNodeId(q.getImportedNodeId())
        .createdAt(q.getCreatedAt())
        .build();
  }
}
