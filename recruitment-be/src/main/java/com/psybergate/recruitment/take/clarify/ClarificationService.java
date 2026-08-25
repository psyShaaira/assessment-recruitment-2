package com.psybergate.recruitment.take.clarify;

import com.psybergate.recruitment.take.clarify.dto.ClarificationRequestDto;
import com.psybergate.recruitment.take.clarify.dto.ClarificationResponse;

import java.util.UUID;

public interface ClarificationService {

    ClarificationResponse clarify(UUID candidateId, UUID assessmentId, ClarificationRequestDto request);
}
