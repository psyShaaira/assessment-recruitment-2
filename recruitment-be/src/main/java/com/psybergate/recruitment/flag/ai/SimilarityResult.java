package com.psybergate.recruitment.flag.ai;

import com.psybergate.recruitment.flag.domain.FlagReason;
import com.psybergate.recruitment.flag.domain.RiskLevel;

public record SimilarityResult(
    RiskLevel risk,
    FlagReason reason,
    double maxSimilarity,
    String rationale
) {}
