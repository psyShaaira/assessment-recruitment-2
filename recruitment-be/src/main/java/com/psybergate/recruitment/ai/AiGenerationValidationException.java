package com.psybergate.recruitment.ai;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The AI provider responded successfully but the generated content didn't parse into a
 * usable question (malformed JSON, missing fields, MCQ correctness violation, unsupported
 * languageHint, etc). Distinct from {@link AiCommunicationException} et al., which mean the
 * provider itself failed — this means the provider succeeded but produced something unusable.
 */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class AiGenerationValidationException extends RuntimeException {

    public AiGenerationValidationException(String message) {
        super(message);
    }
}
