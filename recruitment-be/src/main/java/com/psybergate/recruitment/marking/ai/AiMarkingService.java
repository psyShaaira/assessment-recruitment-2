package com.psybergate.recruitment.marking.ai;

import com.psybergate.recruitment.marking.ai.dto.AiMarkingSuggestionResponse;
import java.util.UUID;

public interface AiMarkingService {
    AiMarkingSuggestionResponse generateSuggestion(UUID submissionId, UUID questionId);

    AiMarkingSuggestionResponse getSuggestion(UUID submissionId, UUID questionId);
}
