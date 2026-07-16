package com.quant.controller;

import java.io.IOException;
import java.util.List;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.quant.dto.study.*;
import com.quant.service.StudyService;
import com.quant.service.StudyUploadService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/study")
@RequiredArgsConstructor
public class StudyController {

  private final StudyService studyService;
  private final StudyUploadService uploadService;

  @GetMapping("/home")
  public HomeDataDTO home() {
    return studyService.getHome();
  }

  @GetMapping("/courses/{id}")
  public CourseDetailDTO courseDetail(@PathVariable Long id) {
    return studyService.getCourseDetail(id);
  }

  @GetMapping("/nodes/{id}")
  public NodeDetailDTO nodeDetail(@PathVariable Long id) {
    return studyService.getNodeDetail(id);
  }

  @GetMapping("/nodes/{id}/quizzes")
  public List<QuizDTO> quizzes(@PathVariable Long id) {
    return studyService.getQuizzes(id);
  }

  @PostMapping("/quizzes/{id}/answer")
  public QuizAnswerDTO answer(@PathVariable Long id, @RequestParam("picked") String picked) {
    return studyService.answerQuiz(id, picked);
  }

  @PostMapping("/upload")
  public UploadResultDTO upload(
      @RequestParam("file") MultipartFile file,
      @RequestParam(value = "title", required = false) String title)
      throws IOException {
    return uploadService.uploadAndCreateCourse(file, title);
  }

  @PostMapping("/nodes/{id}/generate-card")
  public NodeDetailDTO generateCard(@PathVariable Long id) {
    return studyService.generateCards(id);
  }
}
