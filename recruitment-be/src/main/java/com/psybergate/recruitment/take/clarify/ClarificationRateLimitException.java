package com.psybergate.recruitment.take.clarify;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
public class ClarificationRateLimitException extends RuntimeException {

    public ClarificationRateLimitException(String scope) {
        super("Clarification limit reached (" + scope + "). You can continue answering the assessment.");
    }
}
