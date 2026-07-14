package com.quant.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/admin/upload")
@CrossOrigin(origins = "*")
public class NoteImageUploadController {

    private static final long MAX_SIZE_BYTES = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp");

    @PostMapping("/note-image")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("INVALID_FILE: 文件为空");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException("INVALID_FILE_TYPE: 只支持 PNG/JPEG/GIF/WEBP");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new IllegalArgumentException("FILE_TOO_LARGE: 图片不能超过 5MB");
        }

        String ext = contentType.substring("image/".length());
        if ("jpeg".equals(ext)) ext = "jpg";

        String yyyymm = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        Path dir = Paths.get("uploads", "notes", yyyymm);
        Files.createDirectories(dir);

        String filename = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path target = dir.resolve(filename);
        file.transferTo(target.toFile());

        String url = "/uploads/notes/" + yyyymm + "/" + filename;
        log.info("note image uploaded: {}", url);
        return Map.of("url", url);
    }
}