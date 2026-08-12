package com.psybergate.recruitment.ai;

import com.psybergate.recruitment.ai.client.AiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Service
@Primary
@RequiredArgsConstructor
public class AiServiceImpl implements AiService {

    private final AiClient aiClient;

    @Override
    public String prompt(String prompt) {
        validate(prompt);
        return aiClient.sendPrompt(prompt);
    }

    @Override
    public String promptForJson(String prompt) {
        validate(prompt);
        return aiClient.sendPromptForJson(prompt);
    }

    private void validate(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Prompt must not be null or blank");
        }
    }
}
