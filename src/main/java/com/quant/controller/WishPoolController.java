package com.quant.controller;

import com.quant.dto.wishpool.WishSubmitRequest;
import com.quant.service.WishPoolService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/wishes")
@CrossOrigin(origins = "*")
public class WishPoolController {

    private final WishPoolService wishPoolService;

    public WishPoolController(WishPoolService wishPoolService) {
        this.wishPoolService = wishPoolService;
    }

    @PostMapping
    public Map<String, String> submitWish(@Valid @RequestBody WishSubmitRequest request) {
        wishPoolService.submitWish(request);
        return Map.of("message", "已收到许愿，我们会认真评估");
    }
}
