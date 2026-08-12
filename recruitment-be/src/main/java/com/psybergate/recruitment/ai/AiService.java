package com.psybergate.recruitment.ai;

public interface AiService {

    String prompt(String prompt);

    /** Same as {@link #prompt} but instructs the provider to return valid JSON only. */
    String promptForJson(String prompt);
}
