package com.psybergate.recruitment.ai.client;

public interface AiClient {

    String sendPrompt(String prompt);

    /** Same as {@link #sendPrompt} but instructs the provider to return valid JSON only. */
    String sendPromptForJson(String prompt);
}
