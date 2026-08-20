# AI Auto-Flagging — Software Design Document

## 1. Architecture Overview

The AI auto-flagging feature integrates into the existing submission pipeline as an asynchronous post-processing step. It follows the established patterns: `AiService` for Groq communication, prompt-builder components for prompt construction, and the existing `SubmissionFlagService` for flag creation.

```
┌──────────────────────────────────────────────────────────────────────┐
│                     submitAssessment() transaction                    │
│  autoMarkMcq() → scoreUnansweredQuestions() → publish event ─────────┼──┐
└──────────────────────────────────────────────────────────────────────┘  │
                                                                          ▼
┌──────────────────────────────────────────────────────────────────────────┐
│               AiFlaggingListener (@TransactionalEventListener)           │
│                                                                          │
│  1. Gather submission context (timing, answers, questions)               │
│  2. Run AI analysis via AiFlaggingService                                │
│  3. Run cross-submission similarity check                                │
│  4. Persist FlaggingRiskAssessment                                       │
│  5. Auto-create flag if HIGH risk                                        │
└──────────────────────────────────────────────────────────────────────────┘
```

### Design Decisions

| Decision | Rationale |
|----------|-----------|
| `@TransactionalEventListener(AFTER_COMMIT)` | Ensures submission is fully committed before analysis begins; failure doesn't roll back the submission. |
| `@Async` on the listener | Frees the HTTP thread immediately; analysis runs on a background pool. |
| Separate prompt builder component | Follows `AiMarkingPromptBuilder` pattern — no dependencies, testable in isolation. |
| Single AI call per submission | Batches all signals (timing + content) into one prompt to minimize latency and API usage. |
| Algorithmic similarity as separate step | Doesn't need AI, runs deterministically, can flag even when Groq is down. |
| Upsert semantics for risk assessments | Re-analysis overwrites previous result — only latest matters. |

---

## 2. Package Structure

```
com.psybergate.recruitment.flag/
├── ai/
│   ├── AiFlaggingService.java              (interface)
│   ├── AiFlaggingServiceImpl.java          (implementation)
│   ├── AiFlaggingPromptBuilder.java        (@Component — builds prompt)
│   ├── AiFlaggingListener.java             (@TransactionalEventListener)
│   ├── AiFlaggingProperties.java           (@ConfigurationProperties)
│   ├── SubmissionAnalysisContext.java       (data record for prompt builder)
│   ├── SimilarityCheckService.java         (interface)
│   ├── SimilarityCheckServiceImpl.java     (Jaccard similarity)
│   └── dto/
│       ├── AiFlaggingResult.java           (parsed AI response)
│       └── RiskAssessmentResponse.java     (API response DTO)
├── domain/
│   ├── FlaggingRiskAssessment.java         (JPA entity — NEW)
│   └── FlagReason.java                     (existing enum — no changes)
├── repository/
│   └── FlaggingRiskAssessmentRepository.java (NEW)
├── SubmissionFlagService.java              (existing — no interface changes)
├── SubmissionFlagServiceImpl.java          (existing — no changes)
└── SubmissionFlagController.java           (existing — add risk assessment endpoint)
```

---

## 3. Domain Model

### 3.1 New Entity: `FlaggingRiskAssessment`

```java
@Entity
@Table(name = "flagging_risk_assessments")
public class FlaggingRiskAssessment {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "submission_id", nullable = false, unique = true)
    private UUID submissionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RiskLevel risk;  // HIGH, MEDIUM, LOW

    @Column(columnDefinition = "TEXT", nullable = false)
    private String reasons;  // JSON array of FlagReason strings

    @Column(columnDefinition = "TEXT", nullable = false)
    private String rationale;

    @Column(nullable = false)
    private double confidence;

    @Column(name = "analyzed_at", nullable = false)
    private Instant analyzedAt;

    @Column(name = "prompt_version", nullable = false, length = 10)
    private String promptVersion;

    @Column(name = "flag_created")
    private boolean flagCreated;  // whether auto-flag was created
}
```

### 3.2 New Enum: `RiskLevel`

```java
public enum RiskLevel {
    HIGH, MEDIUM, LOW
}
```

### 3.3 Application Event

```java
public record SubmissionCompletedEvent(UUID submissionId, UUID assessmentId) {}
```

Published from `CandidateTakeServiceImpl.submitAssessment()` after the existing marking steps.

---

## 4. Component Design

### 4.1 `AiFlaggingListener`

```java
@Component
@RequiredArgsConstructor
public class AiFlaggingListener {

    private final AiFlaggingService aiFlaggingService;
    private final AiFlaggingProperties properties;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubmissionCompleted(SubmissionCompletedEvent event) {
        if (!properties.isAiEnabled()) return;
        aiFlaggingService.analyze(event.submissionId());
    }
}
```

### 4.2 `AiFlaggingService` Interface

```java
public interface AiFlaggingService {
    /**
     * Performs full integrity analysis on a completed submission:
     * AI content/timing analysis + cross-submission similarity.
     * Persists risk assessment and auto-flags if warranted.
     */
    void analyze(UUID submissionId);

    /**
     * Retrieves the stored risk assessment for a submission (recruiter view).
     */
    Optional<RiskAssessmentResponse> getRiskAssessment(UUID submissionId);
}
```

### 4.3 `AiFlaggingServiceImpl` — Core Flow

```java
@Service
@Transactional
@RequiredArgsConstructor
public class AiFlaggingServiceImpl implements AiFlaggingService {

    private final AiService aiService;
    private final AiFlaggingPromptBuilder promptBuilder;
    private final AiFlaggingProperties properties;
    private final SimilarityCheckService similarityCheckService;
    private final FlaggingRiskAssessmentRepository riskAssessmentRepository;
    private final SubmissionFlagService flagService;
    private final SubmissionFlagRepository flagRepository;
    private final CandidateSubmissionRepository submissionRepository;
    private final CandidateAnswerRepository answerRepository;
    private final AssessmentRepository assessmentRepository;
    private final AssessmentQuestionRepository assessmentQuestionRepository;
    private final QuestionRepository questionRepository;

    private static final UUID SYSTEM_ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final String SYSTEM_ACTOR_USERNAME = "SYSTEM";

    @Override
    public void analyze(UUID submissionId) {
        // 1. Skip if open flag already exists
        if (flagRepository.existsBySubmissionIdAndStatusIn(submissionId, OPEN_STATUSES)) {
            log.info("Skipping AI flagging — open flag already exists for submission {}", submissionId);
            return;
        }

        // 2. Build analysis context
        SubmissionAnalysisContext context = buildContext(submissionId);

        // 3. AI analysis (graceful failure)
        AiFlaggingResult aiResult = runAiAnalysis(context);

        // 4. Cross-submission similarity (independent of AI)
        SimilarityResult similarityResult = similarityCheckService.check(context);

        // 5. Merge results — take highest risk
        AiFlaggingResult merged = mergeResults(aiResult, similarityResult);

        // 6. Persist risk assessment (upsert)
        FlaggingRiskAssessment assessment = persistRiskAssessment(submissionId, merged);

        // 7. Auto-flag if HIGH
        if (merged.risk() == RiskLevel.HIGH) {
            tryCreateFlag(submissionId, merged, assessment);
        }
    }

    private AiFlaggingResult runAiAnalysis(SubmissionAnalysisContext context) {
        try {
            String prompt = promptBuilder.build(context);
            String rawJson = aiService.promptForJson(prompt);
            return parseResult(rawJson);
        } catch (Exception e) {
            log.warn("AI flagging analysis failed, skipping: {}", e.getMessage());
            return AiFlaggingResult.LOW_DEFAULT;
        }
    }
}
```

### 4.4 `AiFlaggingPromptBuilder`

Stateless `@Component` — takes a `SubmissionAnalysisContext` record and produces a prompt string. No injected services.

**Input (`SubmissionAnalysisContext`):**
```java
public record SubmissionAnalysisContext(
    UUID submissionId,
    UUID assessmentId,
    String assessmentTitle,
    int timeLimitMinutes,
    long actualDurationSeconds,
    int questionCount,
    List<AnswerContext> answers
) {}

public record AnswerContext(
    String questionTitle,
    String questionType,    // MCQ, TEXT, CODE_SUBMISSION
    String difficulty,      // EASY, MEDIUM, HARD
    int maxScore,
    String answerContent,   // textContent (null for MCQ)
    Instant savedAt,
    long secondsSinceStart
) {}
```

**Prompt structure:**
1. System instruction: "You are an integrity analysis system..."
2. Assessment metadata (time limit, question count, actual duration)
3. Per-answer timeline (type, difficulty, content length, save timestamp offset)
4. Full TEXT/CODE answer contents (only non-MCQ)
5. Evaluation criteria (timing anomaly indicators, AI-content indicators, suspicious patterns)
6. Response schema: `{"risk": "HIGH|MEDIUM|LOW", "reasons": ["TIMING_ANOMALY", ...], "rationale": "...", "confidence": 0.0-1.0}`

**Prompt version**: `"v1"` — stored with each assessment for reproducibility.

### 4.5 `SimilarityCheckService`

```java
@Service
@RequiredArgsConstructor
public class SimilarityCheckServiceImpl implements SimilarityCheckService {

    private final CandidateAnswerRepository answerRepository;
    private final CandidateSubmissionRepository submissionRepository;
    private final AiFlaggingProperties properties;

    @Override
    public SimilarityResult check(SubmissionAnalysisContext context) {
        // 1. Find other SUBMITTED submissions for same assessment
        // 2. For each TEXT/CODE answer, compute Jaccard word-set similarity
        //    against corresponding answers in other submissions
        // 3. If any pair exceeds threshold → return HIGH + COPIED_ANSWERS
        // 4. Otherwise → return LOW
    }

    private double jaccardSimilarity(String textA, String textB) {
        Set<String> wordsA = normalize(textA);
        Set<String> wordsB = normalize(textB);
        Set<String> intersection = new HashSet<>(wordsA);
        intersection.retainAll(wordsB);
        Set<String> union = new HashSet<>(wordsA);
        union.addAll(wordsB);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private Set<String> normalize(String text) {
        // lowercase, strip punctuation, split on whitespace, filter stopwords
    }
}
```

### 4.6 `AiFlaggingProperties`

```java
@ConfigurationProperties(prefix = "flagging")
public record AiFlaggingProperties(
    boolean aiEnabled,
    double highThreshold,
    double mediumThreshold,
    double similarityThreshold,
    int timeoutSeconds
) {
    public AiFlaggingProperties {
        if (highThreshold <= 0) highThreshold = 0.8;
        if (mediumThreshold <= 0) mediumThreshold = 0.5;
        if (similarityThreshold <= 0) similarityThreshold = 0.8;
        if (timeoutSeconds <= 0) timeoutSeconds = 30;
    }
}
```

---

## 5. API Design

### 5.1 New Endpoint: Get Risk Assessment

```
GET /api/submissions/{submissionId}/risk-assessment
```

**Authorization**: `ROLE_ADMIN` or `ROLE_RECRUITER`

**Response** (200):
```json
{
  "submissionId": "uuid",
  "risk": "HIGH",
  "reasons": ["TIMING_ANOMALY", "AI_GENERATED_CONTENT"],
  "rationale": "Candidate completed a 60-minute assessment in 4 minutes with polished 400-word essay answers...",
  "confidence": 0.92,
  "analyzedAt": "2026-08-19T10:30:00Z",
  "promptVersion": "v1",
  "flagCreated": true
}
```

**Response** (404): No risk assessment exists for this submission.

### 5.2 Modified: Submission List/Detail

The existing `ResultSummaryResponse` (or equivalent list DTO) gains an optional field:

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
private RiskLevel aiRiskLevel;  // null if no assessment exists, or LOW (not shown)
```

Only `MEDIUM` and `HIGH` are surfaced in list views.

---

## 6. Database Schema

### Migration V26: `flagging_risk_assessments`

```sql
CREATE TABLE flagging_risk_assessments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    submission_id   UUID NOT NULL UNIQUE REFERENCES candidate_submissions(id) ON DELETE CASCADE,
    risk            VARCHAR(10) NOT NULL,
    reasons         TEXT NOT NULL,          -- JSON array: ["TIMING_ANOMALY", ...]
    rationale       TEXT NOT NULL,
    confidence      DOUBLE PRECISION NOT NULL,
    analyzed_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    prompt_version  VARCHAR(10) NOT NULL,
    flag_created    BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_flagging_risk_assessments_risk
    ON flagging_risk_assessments(risk) WHERE risk IN ('HIGH', 'MEDIUM');
```

---

## 7. Configuration

Added to `application.yaml`:

```yaml
flagging:
  ai-enabled: true
  high-threshold: 0.8
  medium-threshold: 0.5
  similarity-threshold: 0.8
  timeout-seconds: 30
```

Async executor config (new or reuse existing):

```yaml
spring:
  task:
    execution:
      pool:
        core-size: 2
        max-size: 4
        queue-capacity: 50
      thread-name-prefix: ai-flagging-
```

---

## 8. Event Flow (Sequence)

```
Candidate                  TakeService          EventPublisher         AiFlaggingListener        AiFlaggingService
    │                          │                      │                        │                        │
    ├── submitAssessment() ───►│                      │                        │                        │
    │                          ├── autoMarkMcq()      │                        │                        │
    │                          ├── scoreUnanswered()  │                        │                        │
    │                          ├── publishEvent() ───►│                        │                        │
    │◄── 200 OK ──────────────┤                      │                        │                        │
    │                          │                      ├── AFTER_COMMIT ───────►│                        │
    │                          │                      │                        ├── @Async ─────────────►│
    │                          │                      │                        │                        ├── buildContext()
    │                          │                      │                        │                        ├── promptBuilder.build()
    │                          │                      │                        │                        ├── aiService.promptForJson()
    │                          │                      │                        │                        ├── similarityCheck()
    │                          │                      │                        │                        ├── persistRiskAssessment()
    │                          │                      │                        │                        ├── createFlag() [if HIGH]
    │                          │                      │                        │                        │
```

---

## 9. Error Handling

| Failure | Behavior |
|---------|----------|
| Groq unavailable (timeout, 5xx, rate limit) | Log warning, skip AI analysis, still run similarity check |
| Groq returns unparseable response | Log error, treat as LOW risk, persist partial assessment |
| Similarity check fails (DB error) | Log error, persist AI-only result |
| Open flag already exists | Skip entire analysis, log info |
| Flag creation fails (409 race condition) | Mark `flagCreated = false`, log conflict |
| Submission not found (deleted between event and analysis) | Log warning, exit cleanly |

---

## 10. Testing Strategy

### Unit Tests

| Class | Test Focus |
|-------|------------|
| `AiFlaggingPromptBuilderTest` | Prompt contains timing data, answer contents, correct JSON schema instruction |
| `AiFlaggingServiceImplTest` | Mock `AiService` + repos; verify HIGH→flag, MEDIUM→no flag, graceful failure paths |
| `SimilarityCheckServiceImplTest` | Jaccard calculation accuracy, threshold boundary cases, empty text handling |
| `AiFlaggingListenerTest` | Verifies `@Async` + property gate (`ai-enabled: false` → no call) |

### Integration Tests

| Class | Test Focus |
|-------|------------|
| `AiFlaggingControllerIntegrationTest` | GET endpoint returns risk assessment, 404 when none exists, 403 for candidates |

### Mutation Testing

All new classes under `flag/ai/` are included in PIT scope (they're within `com.psybergate.recruitment.*`). Target: maintain 29% threshold.

---

## 11. Security Considerations

- **No PII in prompts**: Only question text, answer text, and timing metadata leave the system.
- **System actor**: `SYSTEM_ACTOR_ID` is a zeroed UUID constant — never maps to a real staff account. Audit trail clearly distinguishes AI-created flags.
- **Rate limiting**: One AI call per submission, triggered only on status transition. No candidate-triggerable amplification.
- **Data retention**: Risk assessments are tied to submissions via CASCADE delete — when a submission is deleted, the assessment goes with it.

---

## 12. Future Extensions (Not in Scope)

- Embedding-based similarity (replace Jaccard with vector similarity for paraphrase detection)
- Batch re-analysis of historical submissions
- Configurable per-assessment flagging rules
- Frontend "AI insights" panel showing detailed analysis breakdown
- Webhook/notification to recruiters on HIGH-risk detection
