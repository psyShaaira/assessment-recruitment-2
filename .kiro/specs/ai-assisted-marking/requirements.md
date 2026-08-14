# Requirements Document

## Introduction

This feature adds AI-assisted marking for non-MCQ questions (TEXT and
CODE_SUBMISSION) to the existing manual marking workflow in the `marking`
domain. MCQ questions are already scored automatically and remain out of
scope. On demand, a recruiter can ask the system to generate an AI-produced
suggested score and rationale for a candidate's answer to a TEXT or
CODE_SUBMISSION question. The suggestion is generated using the existing
`AiService` abstraction (from the `ai-integration-foundation` feature) and is
stored separately from the recruiter's final `Answer_Score` ("Mark"). The
recruiter remains solely responsible for the final score: the AI suggestion
is advisory only, never written automatically into the final mark, and a
failure to generate a suggestion never blocks the existing manual marking
endpoints.

This is a backend-only feature. No new frontend UI is in scope; the
capability is exposed as new REST endpoints consumed by future frontend work.

## Glossary

- **AI_Marking_Service**: The new application-level Spring service that
  generates and retrieves AI marking suggestions for non-MCQ candidate
  answers, using `AiService` for the underlying AI call.
- **AI_Service**: The existing provider-independent service (from
  `ai-integration-foundation`) that accepts a plain text prompt and returns a
  plain text response, or throws one of `AiAuthenticationException`,
  `AiCommunicationException`, `AiTimeoutException`, `AiRateLimitException`,
  `AiResponseException`.
- **AI_Marking_Suggestion**: A stored record produced by the AI_Marking_Service
  containing a suggested score, rationale text, the identifier of the
  Candidate_Answer it evaluates, and the instant it was generated.
- **Candidate_Answer**: The existing entity representing a candidate's
  answer to a single question within a submission.
- **Answer_Score**: The existing entity (also referred to as the "Mark")
  representing the recruiter's final, authoritative score and feedback for a
  Candidate_Answer.
- **Question**: The existing entity representing a question, with a `type` of
  `MCQ`, `TEXT`, `CODE_SUBMISSION`, or `GROUP`.
- **Non_MCQ_Question**: A Question whose type is `TEXT` or `CODE_SUBMISSION`.
- **MCQ_Question**: A Question whose type is `MCQ`.
- **GROUP_Question**: A Question whose type is `GROUP`; it has no
  Candidate_Answer of its own but has member questions that are individually
  typed (e.g. `TEXT`, `CODE_SUBMISSION`, `MCQ`).
- **Marking_Recruiter**: An authenticated user holding the `RECRUITER` role
  or the `ADMIN` role.
- **GlobalExceptionHandler**: The existing `@RestControllerAdvice` in
  `common/` that maps `@ResponseStatus`-annotated exceptions to
  `ProblemDetail` HTTP responses.

---

## Requirements

### Requirement 1: Triggering AI-Assisted Marking

**User Story:** As a recruiter, I want to request an AI-generated marking
suggestion for a candidate's text or code answer, so that I have a starting
point for evaluating the answer without the system marking it for me
automatically.

#### Acceptance Criteria

1. WHEN a Marking_Recruiter requests an AI marking suggestion for a
   Candidate_Answer to a Non_MCQ_Question, and that Candidate_Answer has
   non-null, non-blank answer content, THE AI_Marking_Service SHALL send a
   prompt derived from the question and the answer content to the
   AI_Service, SHALL store the returned result as a new
   AI_Marking_Suggestion linked to that Candidate_Answer, and SHALL return
   that AI_Marking_Suggestion to the Marking_Recruiter.
2. IF a Marking_Recruiter requests an AI marking suggestion for a question
   whose type is `MCQ` or `GROUP`, THEN THE AI_Marking_Service SHALL reject
   the request with an HTTP 400 response indicating the question type is
   not eligible for AI-assisted marking, and SHALL NOT call the AI_Service.
3. IF a Marking_Recruiter requests an AI marking suggestion for a
   Non_MCQ_Question for which no Candidate_Answer exists, or whose
   Candidate_Answer content is null or blank, THEN THE AI_Marking_Service
   SHALL reject the request with an HTTP 400 response indicating there is
   no answer content to evaluate, and SHALL NOT call the AI_Service.
4. WHEN a Marking_Recruiter requests an AI marking suggestion for a
   Candidate_Answer that already has a stored AI_Marking_Suggestion, THE
   AI_Marking_Service SHALL send a new prompt to the AI_Service, SHALL
   replace the previously stored AI_Marking_Suggestion for that
   Candidate_Answer with the new result, and SHALL return the replacement
   AI_Marking_Suggestion to the Marking_Recruiter.
5. IF a request to generate or retrieve an AI_Marking_Suggestion references
   a submission, Candidate_Answer, or question that does not exist, or that
   exists but does not belong to the specified submission, THEN THE
   AI_Marking_Service SHALL reject the request with an HTTP 404 response.

---

### Requirement 2: AI Marking Suggestion Content

**User Story:** As a recruiter, I want the AI suggestion to include both a
score and a written rationale, so that I can understand why the AI arrived
at that score before deciding on the final mark.

#### Acceptance Criteria

1. THE AI_Marking_Suggestion SHALL include a suggested score expressed as a
   non-negative integer, a rationale text between 1 and 2000 characters
   explaining the suggested score, the identifier of the Candidate_Answer it
   evaluates, and the instant it was generated.
2. THE AI_Marking_Service SHALL constrain the suggested score stored in an
   AI_Marking_Suggestion to the range zero to the associated question's
   maximum score, inclusive.
3. IF the numeric suggested score extracted from the AI_Service response is
   less than zero or greater than the associated question's maximum score,
   THEN THE AI_Marking_Service SHALL clamp the stored suggested score to the
   nearest boundary of that range.
4. IF the AI_Marking_Service cannot extract both a numeric suggested score
   and a rationale text between 1 and 2000 characters from the AI_Service
   response, THEN THE AI_Marking_Service SHALL throw an exception distinct
   from the AI_Service's own exceptions and SHALL NOT store an
   AI_Marking_Suggestion for that request.
5. IF the AI_Marking_Service throws the exception described in Criterion 4,
   THEN THE GlobalExceptionHandler SHALL map that exception to an HTTP 502
   response.

---

### Requirement 3: Prompt Content Boundaries

**User Story:** As a system operator, I want the AI prompt to contain only
the data needed to mark the specific answer, so that unrelated candidate
data is never sent to the external AI provider.

#### Acceptance Criteria

1. THE AI_Marking_Service SHALL include, in the prompt sent to the
   AI_Service for a Non_MCQ_Question and its associated Candidate_Answer
   being marked, exactly the following elements: the question title, the
   question body, the question maximum score, and the content of the
   Candidate_Answer being evaluated.
2. WHERE the Non_MCQ_Question being evaluated is a `CODE_SUBMISSION`
   question with a configured language hint, THE AI_Marking_Service SHALL
   include the language hint in the prompt sent to the AI_Service.
3. THE AI_Marking_Service SHALL NOT include, in the prompt sent to the
   AI_Service, any data beyond the elements specified in Criteria 1 and 2,
   including but not limited to: any other candidate's answer content, any
   other submission's data, any other question's content, and candidate
   personal information (e.g., candidate name, email address, or other
   contact or identity details).

---

### Requirement 4: Retrieval and Traceability

**User Story:** As a recruiter, I want to see the AI suggestion for an
answer separately from the final mark, so that I can compare the two and
retain a record of what the AI proposed.

#### Acceptance Criteria

1. THE AI_Marking_Suggestion SHALL be persisted separately from the
   Answer_Score entity and SHALL NOT modify or create an Answer_Score
   record.
2. WHEN a Marking_Recruiter requests the current AI_Marking_Suggestion for a
   Candidate_Answer that has one stored, THE AI_Marking_Service SHALL
   return the most recently generated AI_Marking_Suggestion for that
   Candidate_Answer.
3. IF a Marking_Recruiter requests the AI_Marking_Suggestion for a
   Candidate_Answer that exists but for which none has been generated,
   THEN THE AI_Marking_Service SHALL reject the request with an HTTP 404
   response indicating that no suggestion exists.
4. THE AI_Marking_Suggestion SHALL record the identifier of the
   Candidate_Answer it evaluates in a field that is separate and distinct
   from the identifier of the Marking_Recruiter who records the final
   Answer_Score for that Candidate_Answer, such that the two identifiers
   reference different entity types and are never stored in the same
   field.
5. IF a Marking_Recruiter requests the AI_Marking_Suggestion for a
   Candidate_Answer identifier that does not correspond to any existing
   Candidate_Answer, THEN THE AI_Marking_Service SHALL reject the request
   with an HTTP 404 response indicating that the Candidate_Answer does not
   exist, distinct from the response used when no suggestion has been
   generated.

---

### Requirement 5: Relationship to Manual Marking Workflow

**User Story:** As a recruiter, I want the AI suggestion to remain advisory,
so that I stay in control of every candidate's final score.

#### Acceptance Criteria

1. THE AI_Marking_Service SHALL NOT automatically create, update, or
   overwrite an Answer_Score as a result of generating an
   AI_Marking_Suggestion.
2. THE existing Answer_Score creation and update endpoints SHALL accept and
   process requests from a Marking_Recruiter identically whether or not an
   AI_Marking_Suggestion exists for the associated Candidate_Answer, and
   SHALL NOT impose any additional validation, restriction, or behavior
   change based on the presence, absence, or content of an
   AI_Marking_Suggestion.
3. WHEN a Marking_Recruiter creates or updates an Answer_Score for a
   Candidate_Answer, THE score and feedback stored SHALL be exactly the
   values supplied by the Marking_Recruiter in that request, independent of
   the content of any AI_Marking_Suggestion previously generated for that
   Candidate_Answer.
4. WHEN a Marking_Recruiter creates or updates an Answer_Score for a
   Candidate_Answer that has a stored AI_Marking_Suggestion, THE
   AI_Marking_Service SHALL retain that AI_Marking_Suggestion unmodified and
   SHALL NOT delete or alter it as a result of the Answer_Score being
   created or updated.

---

### Requirement 6: Failure Handling

**User Story:** As a recruiter, I want a failed AI suggestion request to
never block me from marking a candidate manually, so that AI provider
outages do not stop the recruitment process.

#### Acceptance Criteria

1. IF the AI_Service throws `AiAuthenticationException`,
   `AiCommunicationException`, `AiTimeoutException`, `AiRateLimitException`,
   or `AiResponseException` while the AI_Marking_Service is generating an
   AI_Marking_Suggestion, THEN THE AI_Marking_Service SHALL propagate that
   exception without storing an AI_Marking_Suggestion, and THE
   GlobalExceptionHandler SHALL map it to its existing corresponding HTTP
   error response.
2. IF generating an AI_Marking_Suggestion for a Candidate_Answer fails for
   any reason, THEN THE failure SHALL have no effect on any previously
   stored Answer_Score or AI_Marking_Suggestion for that Candidate_Answer or
   any other Candidate_Answer, and THE AI_Marking_Service SHALL ensure that
   no partial or incomplete AI_Marking_Suggestion is persisted for that
   Candidate_Answer as a result of the failure.
3. IF generating an AI_Marking_Suggestion fails, THEN THE Marking_Recruiter
   SHALL remain able to manually record an Answer_Score for the same
   Candidate_Answer without first resolving the failure.
4. IF the AI_Marking_Service encounters a failure while generating an
   AI_Marking_Suggestion that is not one of the five exceptions named in
   Criterion 1, THEN THE GlobalExceptionHandler SHALL return an HTTP error
   response observable to the caller, and no AI_Marking_Suggestion SHALL be
   stored for that Candidate_Answer as a result of the failure.

---

### Requirement 7: Access Control

**User Story:** As a system operator, I want only recruiters and admins to
trigger or view AI marking suggestions, so that candidates and other
unauthorized users cannot access AI-generated marking data.

#### Acceptance Criteria

1. THE AI_Marking_Service SHALL restrict requests to generate or retrieve an
   AI_Marking_Suggestion to authenticated users holding the `RECRUITER` role
   or the `ADMIN` role.
2. IF an authenticated request to generate or retrieve an
   AI_Marking_Suggestion is made by a user who does not hold the
   `RECRUITER` role or the `ADMIN` role, THEN THE AI_Marking_Service SHALL
   reject the request with an HTTP 403 response and SHALL NOT generate,
   persist, or return any AI_Marking_Suggestion data as a result of the
   rejected request.
3. IF a request to generate or retrieve an AI_Marking_Suggestion is made
   without valid authentication, THEN THE AI_Marking_Service SHALL reject
   the request with an HTTP 401 response.
