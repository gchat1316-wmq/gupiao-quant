package com.quant.service;

import java.util.*;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.quant.config.AiProperties;
import com.quant.dto.study.*;
import com.quant.entity.*;
import com.quant.repository.*;
import com.quant.service.ai.AiKnowledgeExtractionService;
import com.quant.service.ai.SenseNovaClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class StudyService {

  private final StudyCourseRepository courseRepo;
  private final StudyCategoryRepository categoryRepo;
  private final StudyMaterialRepository materialRepo;
  private final StudyKnowledgeNodeRepository nodeRepo;
  private final StudyCardRepository cardRepo;
  private final StudyQuizRepository quizRepo;
  private final StudyQuizRecordRepository quizRecordRepo;
  private final SenseNovaClient senseNovaClient;
  private final AiKnowledgeExtractionService aiExtractionService;
  private final AiProperties aiProperties;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public HomeDataDTO getHome() {
    List<StudyCourse> myList = courseRepo.findByVisibilityOrderByIdAsc("private");
    List<StudyCourse> pubList = courseRepo.findByVisibilityOrderByIdAsc("public");
    List<StudyCategory> cats = categoryRepo.findAllByOrderBySortAsc();

    MyCourseTabCountsDTO counts =
        MyCourseTabCountsDTO.builder()
            .all(myList.size())
            .created((int) myList.stream().filter(c -> "由我创建".equals(c.getOwner())).count())
            .learning(
                (int) myList.stream().filter(c -> "learning".equals(c.getLearnStatus())).count())
            .pending(
                (int) myList.stream().filter(c -> "pending".equals(c.getLearnStatus())).count())
            .done((int) myList.stream().filter(c -> "done".equals(c.getLearnStatus())).count())
            .build();

    return HomeDataDTO.builder()
        .myCourses(myList.stream().map(this::toCourseSummary).collect(Collectors.toList()))
        .publicCourses(pubList.stream().map(this::toCourseSummary).collect(Collectors.toList()))
        .categories(
            cats.stream()
                .map(c -> new CategoryDTO(c.getId(), c.getName()))
                .collect(Collectors.toList()))
        .myCounts(counts)
        .build();
  }

  public CourseDetailDTO getCourseDetail(Long courseId) {
    StudyCourse course =
        courseRepo
            .findById(courseId)
            .orElseThrow(() -> new IllegalArgumentException("课程不存在: " + courseId));

    List<StudyKnowledgeNode> nodes = nodeRepo.findByCourseIdOrderByLevelAscSortAscIdAsc(courseId);
    List<KnowledgeNodeDTO> tree = buildTree(nodes);

    List<StudyMaterial> materials = materialRepo.findByCourseIdOrderByIdAsc(courseId);

    return CourseDetailDTO.builder()
        .course(toCourseSummary(course))
        .tree(tree)
        .materials(materials.stream().map(this::toMaterial).collect(Collectors.toList()))
        .build();
  }

  public NodeDetailDTO getNodeDetail(Long nodeId) {
    StudyKnowledgeNode node =
        nodeRepo
            .findById(nodeId)
            .orElseThrow(() -> new IllegalArgumentException("知识点不存在: " + nodeId));
    StudyCourse course = courseRepo.findById(node.getCourseId()).orElse(null);

    List<CardDTO> ai =
        cardRepo.findByNodeIdAndCardTypeOrderBySortAscIdAsc(nodeId, "ai_detail").stream()
            .map(this::toCard)
            .collect(Collectors.toList());
    List<CardDTO> flash =
        cardRepo.findByNodeIdAndCardTypeOrderBySortAscIdAsc(nodeId, "flash").stream()
            .map(this::toCard)
            .collect(Collectors.toList());

    int quizCount = quizRepo.findByNodeIdOrderBySortAscIdAsc(nodeId).size();

    return NodeDetailDTO.builder()
        .node(toNodeDTO(node, Collections.emptyList()))
        .aiDetailCards(ai)
        .flashCards(flash)
        .quizCount(quizCount)
        .courseId(node.getCourseId())
        .courseTitle(course != null ? course.getTitle() : null)
        .build();
  }

  public List<QuizDTO> getQuizzes(Long nodeId) {
    List<StudyQuiz> quizzes = quizRepo.findByNodeIdOrderBySortAscIdAsc(nodeId);
    StudyKnowledgeNode node = nodeRepo.findById(nodeId).orElse(null);
    String nodeTitle = node != null ? node.getTitle() : null;
    return quizzes.stream()
        .map(
            q -> {
              List<QuizDTO.QuizOption> opts;
              try {
                List<Map<String, String>> raw =
                    MAPPER.readValue(q.getOptionsJson(), new TypeReference<>() {});
                opts =
                    raw.stream()
                        .map(
                            m ->
                                QuizDTO.QuizOption.builder()
                                    .key(m.get("key"))
                                    .text(m.get("text"))
                                    .build())
                        .collect(Collectors.toList());
              } catch (Exception e) {
                opts = Collections.emptyList();
              }
              return QuizDTO.builder()
                  .id(q.getId())
                  .stem(q.getStem())
                  .options(opts)
                  .relatedNodeTitle(nodeTitle)
                  .build();
            })
        .collect(Collectors.toList());
  }

  public QuizAnswerDTO answerQuiz(Long quizId, String picked) {
    StudyQuiz quiz =
        quizRepo
            .findById(quizId)
            .orElseThrow(() -> new IllegalArgumentException("题目不存在: " + quizId));
    boolean correct = quiz.getAnswer().equalsIgnoreCase(picked);

    StudyQuizRecord rec = new StudyQuizRecord();
    rec.setQuizId(quizId);
    rec.setPicked(picked);
    rec.setCorrect(correct ? 1 : 0);
    quizRecordRepo.save(rec);

    return QuizAnswerDTO.builder()
        .quizId(quizId)
        .correctAnswer(quiz.getAnswer())
        .picked(picked)
        .correct(correct)
        .analysis(quiz.getAnalysis())
        .build();
  }

  /** 为某个知识点生成知识卡片: 1) 若无 AI 详解卡片 → 调 MiniMax 生成 2) 若无闪卡图片 → 调 SenseNova 生成信息图 返回生成后的节点详情 */
  public NodeDetailDTO generateCards(Long nodeId) {
    StudyKnowledgeNode node =
        nodeRepo
            .findById(nodeId)
            .orElseThrow(() -> new IllegalArgumentException("知识点不存在: " + nodeId));

    // 1) 生成 AI 详解卡片 (如果没有)
    List<StudyCard> existingAi =
        cardRepo.findByNodeIdAndCardTypeOrderBySortAscIdAsc(nodeId, "ai_detail");
    if (existingAi.isEmpty()) {
      generateAiDetailCards(node);
    }

    // 2) 生成闪卡图片 (如果没有)
    List<StudyCard> existingFlash =
        cardRepo.findByNodeIdAndCardTypeOrderBySortAscIdAsc(nodeId, "flash");
    if (existingFlash.isEmpty()) {
      generateFlashCard(node);
    }

    return getNodeDetail(nodeId);
  }

  private void generateAiDetailCards(StudyKnowledgeNode node) {
    try {
      String nodeContext = buildNodeContext(node);
      String prompt =
          "请为以下知识点生成详细解析。输出格式要求:\n"
              + "第一部分:内容概述(2-3句话)\n"
              + "第二部分:核心解析(详细解释该知识点的原理和要点,300-500字)\n"
              + "第三部分:思考一下(提出一个引发深入思考的问题,并给出提示)\n\n"
              + "知识点: "
              + node.getTitle()
              + "\n"
              + "概要: "
              + (node.getSummary() != null ? node.getSummary() : "暂无")
              + "\n"
              + "定义: "
              + (node.getDefinition() != null ? node.getDefinition() : "暂无")
              + "\n"
              + (nodeContext.isEmpty() ? "" : "上下文:\n" + nodeContext);

      String systemPrompt = "你是一位资深教育专家,擅长用通俗易懂的方式讲解复杂概念。请始终用简体中文回答。";

      // 优先使用 SenseNova chat, 其次 MiniMax
      String result;
      AiProperties.SenseNova snova = aiProperties.getSensenova();
      if (snova.isEnabled() && snova.getApiKey() != null && !snova.getApiKey().isBlank()) {
        result = senseNovaClient.chatComplete(systemPrompt, prompt);
      } else {
        result = aiExtractionService.extractTextOnly(systemPrompt, prompt);
      }

      // 保存概述卡片
      StudyCard overviewCard = new StudyCard();
      overviewCard.setNodeId(node.getId());
      overviewCard.setCardType("ai_detail");
      overviewCard.setTitle("内容概述");
      overviewCard.setBody(node.getSummary() != null ? node.getSummary() : "");
      overviewCard.setSort(0);
      cardRepo.save(overviewCard);

      // 保存详解卡片
      StudyCard detailCard = new StudyCard();
      detailCard.setNodeId(node.getId());
      detailCard.setCardType("ai_detail");
      detailCard.setTitle("AI详解");
      detailCard.setBody(result);
      detailCard.setSort(1);
      cardRepo.save(detailCard);

      log.info("AI 详解卡片生成完成: nodeId={}", node.getId());
    } catch (Exception e) {
      log.warn("AI 详解卡片生成失败,使用节点信息占位: {}", e.getMessage());
      StudyCard fallback = new StudyCard();
      fallback.setNodeId(node.getId());
      fallback.setCardType("ai_detail");
      fallback.setTitle("内容概述");
      fallback.setBody(
          node.getSummary() != null
              ? node.getSummary()
              : (node.getDefinition() != null ? node.getDefinition() : "暂无内容"));
      fallback.setSort(0);
      cardRepo.save(fallback);
    }
  }

  private void generateFlashCard(StudyKnowledgeNode node) {
    try {
      String imagePrompt = buildFlashCardPrompt(node);
      String imageUrl = senseNovaClient.generateImage(imagePrompt);

      StudyCard flash = new StudyCard();
      flash.setNodeId(node.getId());
      flash.setCardType("flash");
      flash.setTitle(node.getTitle());
      flash.setBody(node.getDefinition() != null ? node.getDefinition() : node.getSummary());
      flash.setImageUrl(imageUrl);
      flash.setSort(0);
      cardRepo.save(flash);

      log.info("闪卡图片生成完成: nodeId={}, imageUrl长度={}", node.getId(), imageUrl.length());
    } catch (Exception e) {
      log.warn("闪卡图片生成失败: {} -> 使用占位图", e.getMessage());
      if (aiProperties.isFallbackToMock()) {
        StudyCard fallback = new StudyCard();
        fallback.setNodeId(node.getId());
        fallback.setCardType("flash");
        fallback.setTitle(node.getTitle());
        fallback.setBody(node.getDefinition() != null ? node.getDefinition() : node.getSummary());
        fallback.setImageUrl(
            "https://dummyimage.com/800x450/1b5e20/ffffff&text=" + encodeForUrl(node.getTitle()));
        fallback.setSort(0);
        cardRepo.save(fallback);
      }
    }
  }

  private String buildNodeContext(StudyKnowledgeNode node) {
    StringBuilder sb = new StringBuilder();
    // 查找父节点
    if (node.getParentId() != null) {
      nodeRepo
          .findById(node.getParentId())
          .ifPresent(
              p -> {
                sb.append("所属章节: ").append(p.getTitle()).append("\n");
                if (p.getSummary() != null) sb.append("章节概要: ").append(p.getSummary()).append("\n");
              });
    }
    // 查找子节点
    List<StudyKnowledgeNode> children =
        nodeRepo.findByCourseIdOrderByLevelAscSortAscIdAsc(node.getCourseId()).stream()
            .filter(n -> node.getId().equals(n.getParentId()))
            .toList();
    if (!children.isEmpty()) {
      sb.append("包含子知识点: ")
          .append(
              children.stream()
                  .map(StudyKnowledgeNode::getTitle)
                  .reduce((a, b) -> a + "、" + b)
                  .orElse(""))
          .append("\n");
    }
    return sb.toString();
  }

  private String buildFlashCardPrompt(StudyKnowledgeNode node) {
    String title = node.getTitle() != null ? node.getTitle() : "知识点";
    String summary = node.getSummary() != null ? node.getSummary() : "";
    String definition = node.getDefinition() != null ? node.getDefinition() : "";

    return "这张信息图以柔和的粉色、淡黄色和浅蓝色为主色调,采用了极具亲和力的可爱卡通风格"
        + "(包含猫咪、拟人化表情等元素)。整体排版清晰,适合学习理解。\n\n"
        + "图表顶部是醒目的主标题\""
        + title
        + "\"。\n\n"
        + "以下是图表中各区块的详细结构和全部文字内容:\n\n"
        + "1. 核心定义区块(左上):\n"
        + (definition.isEmpty() ? "" : "定义: " + definition + "\n\n")
        + "2. 内容概述区块(中间):\n"
        + (summary.isEmpty() ? "" : "概要: " + summary + "\n\n")
        + "3. 关键要点区块(右侧):\n"
        + "列出该知识点的3个关键要点,每个要点配有可爱的图标。\n\n"
        + "图表底部附带一条学习提示(黄色便利贴样式,右上角有一只探出纸箱的猫咪图标)";
  }

  private String encodeForUrl(String s) {
    if (s == null) return "Knowledge";
    try {
      return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
      return "Knowledge";
    }
  }

  // ---------- mapping ----------

  private CourseSummaryDTO toCourseSummary(StudyCourse c) {
    return CourseSummaryDTO.builder()
        .id(c.getId())
        .title(c.getTitle())
        .summary(c.getSummary())
        .coverText(c.getCoverText())
        .coverColor(c.getCoverColor())
        .owner(c.getOwner())
        .visibility(c.getVisibility())
        .status(c.getStatus())
        .progress(c.getProgress())
        .learnStatus(c.getLearnStatus())
        .masteredCnt(c.getMasteredCnt())
        .totalCnt(c.getTotalCnt())
        .learnerCnt(c.getLearnerCnt())
        .categoryId(c.getCategoryId())
        .build();
  }

  private MaterialDTO toMaterial(StudyMaterial m) {
    return MaterialDTO.builder()
        .id(m.getId())
        .fileName(m.getFileName())
        .fileType(m.getFileType())
        .size(m.getSize())
        .parseStatus(m.getParseStatus())
        .progress(m.getProgress())
        .build();
  }

  private CardDTO toCard(StudyCard c) {
    return CardDTO.builder()
        .id(c.getId())
        .cardType(c.getCardType())
        .stage(c.getStage())
        .title(c.getTitle())
        .body(c.getBody())
        .imageUrl(c.getImageUrl())
        .build();
  }

  private KnowledgeNodeDTO toNodeDTO(StudyKnowledgeNode n, List<KnowledgeNodeDTO> children) {
    return KnowledgeNodeDTO.builder()
        .id(n.getId())
        .parentId(n.getParentId())
        .title(n.getTitle())
        .summary(n.getSummary())
        .definition(n.getDefinition())
        .level(n.getLevel())
        .mastered(n.getMastered())
        .children(children)
        .build();
  }

  private List<KnowledgeNodeDTO> buildTree(List<StudyKnowledgeNode> nodes) {
    Map<Long, List<StudyKnowledgeNode>> byParent = new HashMap<>();
    for (StudyKnowledgeNode n : nodes) {
      byParent.computeIfAbsent(n.getParentId(), k -> new ArrayList<>()).add(n);
    }
    return buildChildren(byParent, null);
  }

  private List<KnowledgeNodeDTO> buildChildren(
      Map<Long, List<StudyKnowledgeNode>> byParent, Long parentId) {
    List<StudyKnowledgeNode> children = byParent.getOrDefault(parentId, Collections.emptyList());
    return children.stream()
        .map(c -> toNodeDTO(c, buildChildren(byParent, c.getId())))
        .collect(Collectors.toList());
  }
}
