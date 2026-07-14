package com.quant.controller;

import com.quant.repository.InvestXieboRecentWatchRepository;
import com.quant.repository.InvestXieboStockNoteRepository;
import com.quant.repository.UserRepository;
import com.quant.security.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(NoteImageUploadController.class)
@AutoConfigureMockMvc(addFilters = false)
class NoteImageUploadControllerTest {

    @Autowired MockMvc mvc;
    @MockBean InvestXieboRecentWatchRepository watchRepo;
    @MockBean InvestXieboStockNoteRepository noteRepo;
    @MockBean UserRepository userRepository;
    @MockBean JwtTokenProvider jwtTokenProvider;

    @AfterEach
    void cleanup() throws Exception {
        // Best-effort cleanup of any test uploads
        Path base = Paths.get("uploads", "notes");
        if (Files.exists(base)) {
            Files.walk(base)
                    .sorted((a, b) -> b.toString().length() - a.toString().length())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
        }
    }

    @Test
    void upload_validPng_returnsUrl() throws Exception {
        byte[] data = new byte[1024];
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.png", "image/png", data);

        mvc.perform(multipart("/api/admin/upload/note-image").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.matchesPattern("/uploads/notes/\\d{6}/[a-f0-9]{32}\\.png")));
    }

    @Test
    void upload_tooLarge_returns400() throws Exception {
        byte[] data = new byte[6 * 1024 * 1024]; // 6MB
        MockMultipartFile file = new MockMultipartFile(
                "file", "big.png", "image/png", data);

        mvc.perform(multipart("/api/admin/upload/note-image").file(file))
                .andExpect(status().isBadRequest());
    }

    @Test
    void upload_wrongType_returns400() throws Exception {
        byte[] data = "hello".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.txt", "text/plain", data);

        mvc.perform(multipart("/api/admin/upload/note-image").file(file))
                .andExpect(status().isBadRequest());
    }
}