package com.quant.controller;

import java.util.List;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.quant.dto.xiebo.RecentNoteDto;
import com.quant.dto.xiebo.RecentWatchDto;
import com.quant.service.XieboRecentService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/xiebo/recent")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class XieboRecentController {

  private final XieboRecentService service;

  @GetMapping
  public List<RecentWatchDto> list(@RequestParam(required = false) String type) {
    return service.listAll(type);
  }

  @GetMapping("/{stockCode}/note")
  public RecentNoteDto note(@PathVariable String stockCode) {
    return service.getNote(stockCode);
  }
}
