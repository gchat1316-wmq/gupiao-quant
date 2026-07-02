package com.quant.service;

import com.quant.dto.invest.PoolMetaDTO;
import com.quant.dto.invest.PoolMetaUpdateRequest;
import com.quant.entity.InvestPoolMeta;
import com.quant.repository.InvestPoolMetaRepository;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class InvestPoolMetaService {

    /** 允许的 pool_type 字面量。修改列表时同步更新 ensureInvestPoolMetaSeed 的种子。 */
    public static final Set<String> ALLOWED_POOL_TYPES = Set.of("tech_ai", "innovative_drug", "quality");

    private final InvestPoolMetaRepository repository;
    private final Parser markdownParser;
    private final HtmlRenderer htmlRenderer;

    @Value("${app.upload-dir:uploads}")
    private String uploadDir;

    public InvestPoolMetaService(InvestPoolMetaRepository repository) {
        this.repository = repository;
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, List.of(TablesExtension.create()));
        this.markdownParser = Parser.builder(options).build();
        this.htmlRenderer = HtmlRenderer.builder(options).build();
    }

    @Cacheable(value = "poolMeta", key = "'list'")
    @Transactional(readOnly = true)
    public List<PoolMetaDTO> listAll() {
        return repository.findAllByOrderByDisplayOrderAscPoolTypeAsc().stream()
                .map(this::toDto)
                .toList();
    }

    @Cacheable(value = "poolMeta", key = "#poolType")
    @Transactional(readOnly = true)
    public PoolMetaDTO get(String poolType) {
        return repository.findById(poolType).map(this::toDto).orElse(null);
    }

    @CacheEvict(value = "poolMeta", allEntries = true)
    @Transactional
    public PoolMetaDTO update(String poolType, PoolMetaUpdateRequest req) {
        validatePoolType(poolType);
        InvestPoolMeta meta = repository.findById(poolType)
                .orElseThrow(() -> new IllegalArgumentException("股票池类型不存在：" + poolType));
        if (req.getDisplayName() != null) {
            String name = req.getDisplayName().trim();
            if (name.isEmpty()) throw new IllegalArgumentException("displayName 不能为空");
            meta.setDisplayName(name);
        }
        if (req.getValuationMethodMd() != null) {
            meta.setValuationMethodMd(req.getValuationMethodMd());
            meta.setValuationMethodHtml(renderMarkdown(req.getValuationMethodMd()));
        }
        if (req.getWeeklyOpportunityMd() != null) {
            meta.setWeeklyOpportunityMd(req.getWeeklyOpportunityMd());
            meta.setWeeklyOpportunityHtml(renderMarkdown(req.getWeeklyOpportunityMd()));
        }
        if (req.getDisplayOrder() != null) {
            meta.setDisplayOrder(req.getDisplayOrder());
        }
        InvestPoolMeta saved = repository.save(meta);
        return toDto(saved);
    }

    @CacheEvict(value = "poolMeta", allEntries = true)
    @Transactional
    public Map<String, String> setCoverImage(String poolType, MultipartFile file) throws IOException {
        validatePoolType(poolType);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("封面图不能为空");
        }
        String originalName = file.getOriginalFilename() == null ? "cover" : file.getOriginalFilename();
        String ext = extOf(originalName);
        if (!isAllowedImageExt(ext)) {
            throw new IllegalArgumentException("仅支持 JPG/PNG/WebP/GIF 格式");
        }

        Path dir = Paths.get(uploadDir, "pool-covers").toAbsolutePath().normalize();
        Files.createDirectories(dir);
        String safeName = UUID.randomUUID() + "_" + originalName.replaceAll("[\\\\/:*?\"<>|]", "_");
        Path target = dir.resolve(safeName);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("封面图已保存: {}", target);

        InvestPoolMeta meta = repository.findById(poolType)
                .orElseThrow(() -> new IllegalArgumentException("股票池类型不存在：" + poolType));
        String publicUrl = "/uploads/pool-covers/" + safeName;
        meta.setCoverImageUrl(publicUrl);
        repository.save(meta);
        return Map.of("coverImageUrl", publicUrl);
    }

    private void validatePoolType(String poolType) {
        if (poolType == null || poolType.isBlank()) {
            throw new IllegalArgumentException("poolType 不能为空");
        }
        if (!ALLOWED_POOL_TYPES.contains(poolType)) {
            throw new IllegalArgumentException("不支持的 poolType：" + poolType);
        }
    }

    private String renderMarkdown(String md) {
        if (md == null || md.trim().isEmpty()) return "";
        return htmlRenderer.render(markdownParser.parse(md));
    }

    private PoolMetaDTO toDto(InvestPoolMeta meta) {
        return PoolMetaDTO.builder()
                .poolType(meta.getPoolType())
                .displayName(meta.getDisplayName())
                .coverImageUrl(meta.getCoverImageUrl())
                .valuationMethodMd(meta.getValuationMethodMd())
                .valuationMethodHtml(meta.getValuationMethodHtml())
                .weeklyOpportunityMd(meta.getWeeklyOpportunityMd())
                .weeklyOpportunityHtml(meta.getWeeklyOpportunityHtml())
                .displayOrder(meta.getDisplayOrder())
                .updatedAt(meta.getUpdatedAt())
                .build();
    }

    private String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    private boolean isAllowedImageExt(String ext) {
        return Set.of("jpg", "jpeg", "png", "webp", "gif", "svg").contains(ext);
    }
}