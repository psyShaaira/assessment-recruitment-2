package com.psybergate.recruitment.marking.ai;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_GATEWAY)
public class AiMarkingResponseException extends RuntimeException {

    public AiMarkingResponseException(String message) {
        super(message);
    }
}
