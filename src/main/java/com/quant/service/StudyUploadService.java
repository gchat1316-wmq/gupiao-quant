package com.quant.service;

import com.quant.dto.study.UploadResultDTO;
import com.quant.entity.StudyCourse;
import com.quant.entity.StudyKnowledgeNode;
import com.quant.entity.StudyMaterial;
import com.quant.repository.StudyCourseRepository;
import com.quant.repository.StudyKnowledgeNodeRepository;
import com.quant.repository.StudyMaterialRepository;
import com.quant.service.ai.AiKnowledgeExtractionService;
import com.quant.service.ai.ExtractedNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class StudyUploadService {

    private final StudyCourseRepository courseRepo;
    private final StudyMaterialRepository materialRepo;
    private final StudyKnowledgeNodeRepository nodeRepo;
    private final AiKnowledgeExtractionService aiService;

    @Value("${app.upload-dir:uploads}")
    private String uploadDir;

    public UploadResultDTO uploadAndCreateCourse(MultipartFile file, String title) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }
        String originalName = file.getOriginalFilename() == null ? "untitled" : file.getOriginalFilename();
        String ext = extOf(originalName);
        String resolvedTitle = (title != null && !title.isBlank())
                ? title.trim()
                : deriveTitleFromFileName(originalName);

        Path dir = Paths.get(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        String safeName = UUID.randomUUID() + "_" + originalName.replaceAll("[\\\\/:*?\"<>|]", "_");
        Path target = dir.resolve(safeName);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("文件已保存: {}", target);

        String extracted = tryExtractText(target, ext);

        StudyCourse course = new StudyCourse();
        course.setTitle(resolvedTitle);
        course.setSummary(extracted == null ? null : truncate(extracted, 400));
        course.setCoverText("📥");
        course.setCoverColor("#e8f5e9");
        course.setOwner("由我创建");
        course.setSourceType("upload");
        course.setVisibility("private");
        course.setStatus("processing");
        course.setProgress(40);
        course.setLearnStatus("learning");
        course.setMasteredCnt(0);
        course.setTotalCnt(0);
        course.setLearnerCnt(1);
        StudyCourse saved = courseRepo.save(course);

        StudyMaterial material = new StudyMaterial();
        material.setCourseId(saved.getId());
        material.setFileName(originalName);
        material.setFileType(ext);
        material.setFilePath(target.toString());
        material.setSize(file.getSize());
        material.setParseStatus(extracted != null ? "done" : "pending");
        material.setProgress(extracted != null ? 100 : 0);
        material.setExtractedText(extracted == null ? null : truncate(extracted, 8000));
        materialRepo.save(material);

        // ===== 调用 AI 生成知识树 (或回退到 mock) =====
        String aiMessage;
        try {
            ExtractedNode root = aiService.extract(resolvedTitle, extracted);
            int total = persistTree(saved.getId(), root);
            saved.setTotalCnt(total);
            if (root.getSummary() != null && !root.getSummary().isBlank()) {
                saved.setSummary(truncate(root.getSummary(), 400));
            }
            saved.setStatus("ready");
            saved.setProgress(100);
            courseRepo.save(saved);
            aiMessage = "知识树生成完成,共 " + total + " 个知识点";
        } catch (Exception e) {
            log.error("生成知识树失败,保留 processing 状态", e);
            aiMessage = "文件已保存,知识树生成失败:" + e.getMessage();
        }

        return UploadResultDTO.builder()
                .courseId(saved.getId())
                .title(saved.getTitle())
                .status(saved.getStatus())
                .progress(saved.getProgress())
                .message(aiMessage)
                .build();
    }

    private int persistTree(Long courseId, ExtractedNode root) {
        if (root == null) return 0;
        int[] counter = {0};
        saveRecursive(courseId, null, root, 1, 0, counter);
        return counter[0];
    }

    private void saveRecursive(Long courseId, Long parentId, ExtractedNode n, int level, int sort, int[] counter) {
        StudyKnowledgeNode entity = new StudyKnowledgeNode();
        entity.setCourseId(courseId);
        entity.setParentId(parentId);
        entity.setTitle(truncate(n.getTitle() == null ? "(未命名)" : n.getTitle(), 200));
        entity.setSummary(n.getSummary());
        entity.setDefinition(n.getDefinition());
        entity.setLevel(level);
        entity.setSort(sort);
        entity.setMastered(0);
        StudyKnowledgeNode saved = nodeRepo.save(entity);
        counter[0]++;
        List<ExtractedNode> children = n.getChildren();
        if (children == null) return;
        for (int i = 0; i < children.size(); i++) {
            saveRecursive(courseId, saved.getId(), children.get(i), level + 1, i, counter);
        }
    }

    private String deriveTitleFromFileName(String fileName) {
        String base = fileName == null ? "未命名资料" : fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        // 去掉 uuid 前缀
        if (base.matches("^[0-9a-fA-F-]{8,}_.*")) {
            base = base.substring(base.indexOf('_') + 1);
        }
        return base;
    }

    private String tryExtractText(Path file, String ext) {
        try {
            if ("pdf".equalsIgnoreCase(ext)) {
                try (PDDocument doc = PDDocument.load(file.toFile())) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    int pages = Math.min(doc.getNumberOfPages(), 30);
                    stripper.setStartPage(1);
                    stripper.setEndPage(pages);
                    return stripper.getText(doc);
                }
            }
            if ("txt".equalsIgnoreCase(ext) || "md".equalsIgnoreCase(ext)) {
                byte[] bytes = Files.readAllBytes(file);
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("文本抽取失败: {}", e.getMessage());
        }
        return null;
    }

    private String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
