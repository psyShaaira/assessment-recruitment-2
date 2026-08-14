package com.psybergate.recruitment.question;

import com.psybergate.recruitment.ai.QuestionGenerationService;
import com.psybergate.recruitment.question.dto.GenerateQuestionRequest;
import com.psybergate.recruitment.question.dto.QuestionRequest;
import com.psybergate.recruitment.question.dto.QuestionResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/questions")
@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")
@RequiredArgsConstructor
public class QuestionController {

    private final QuestionService questionService;
    private final QuestionGenerationService questionGenerationService;

    @PostMapping
    public ResponseEntity<QuestionResponse> create(@RequestBody @Valid QuestionRequest request,
                                                   Authentication auth) {
        UUID userId = UUID.fromString((String) auth.getPrincipal());
        return ResponseEntity.status(HttpStatus.CREATED).body(questionService.create(request, userId));
    }

    @GetMapping
    public ResponseEntity<List<QuestionResponse>> list(@RequestParam(required = false) String type,
                                                       @RequestParam(required = false) String tag) {
        return ResponseEntity.ok(questionService.findAll(type, tag));
    }

    @GetMapping("/{id}")
    public ResponseEntity<QuestionResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(questionService.findById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<QuestionResponse> update(@PathVariable UUID id,
                                                   @RequestBody @Valid QuestionRequest request) {
        return ResponseEntity.ok(questionService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        questionService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/generate")
    public ResponseEntity<List<QuestionRequest>> generate(@RequestBody @Valid GenerateQuestionRequest request) {
        return ResponseEntity.ok(questionGenerationService.generate(request));
    }
}
