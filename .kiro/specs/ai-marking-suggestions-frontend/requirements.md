# Requirements Document

## Introduction

This feature surfaces AI-assisted marking suggestions in the existing
recruiter/admin Results & Evaluation view (`features/results`) of
`recruitment-fe`, consuming the two REST endpoints already implemented by
the backend `ai-assisted-marking` feature
(`POST` / `GET /api/submissions/{submissionId}/questions/{questionId}/ai-suggestion`).
For each candidate answer to a TEXT or CODE_SUBMISSION question, a recruiter
can request (or regenerate) an AI-generated suggested score and rationale,
view it alongside the existing manual marking controls, and optionally copy
the suggested score into the manual mark input as an editable starting
point. The suggestion never writes to the final mark automatically, never
blocks the existing manual marking flow on failure, and only the most
recently generated suggestion is ever shown (no suggestion history). MCQ and
GROUP-preamble questions, and answers with no content, are not eligible and
show no AI suggestion controls. This is a frontend-only feature; no backend
changes are in scope.

## Glossary

- **Results_Component**: The existing `recruitment-fe` `features/results`
  component that lists candidate submissions and displays candidate answers
  and marking controls to a Marking_Recruiter.
- **Marking_Recruiter**: An authenticated staff user holding the
  `RECRUITER` role or the `ADMIN` role, matching the existing access
  restriction on the Results_Component's route and on the backend
  marking/AI-suggestion endpoints.
- **Candidate_Answer**: The existing data representing a candidate's answer
  to a single question within a submission, as displayed by the
  Results_Component (unchanged by this feature).
- **Answer_Score**: The existing recruiter-supplied final score and
  feedback for a Candidate_Answer (unchanged by this feature).
- **Manual_Mark_Input**: The existing input field in the Results_Component
  through which a Marking_Recruiter enters or edits the score portion of an
  Answer_Score for a Candidate_Answer.
- **Eligible_Question**: A question whose type is `TEXT` or
  `CODE_SUBMISSION` and whose Candidate_Answer content is non-null and
  non-blank, matching the eligibility enforced by the backend AI-suggestion
  endpoints. This includes individually-markable `TEXT`/`CODE_SUBMISSION`
  sub-questions of a `GROUP` question.
- **AI_Marking_Suggestion**: The suggestion record returned by the backend
  AI-suggestion endpoints, containing the answer identifier, a suggested
  score, the question's maximum score, a rationale text, and the instant it
  was generated.
- **Suggested_Score**: The numeric score field of an AI_Marking_Suggestion.
- **Suggested_Rationale**: The rationale text field of an
  AI_Marking_Suggestion.
- **AI_Marking_Suggestion_Service**: The new Angular service, under
  `core/ai-marking`, that calls the backend's `POST` and `GET`
  `/api/submissions/{submissionId}/questions/{questionId}/ai-suggestion`
  endpoints to generate and retrieve AI_Marking_Suggestion data.
- **Suggestion_Panel**: The area of the Results_Component's answer card,
  for a given Eligible_Question's Candidate_Answer, that shows the request
  control, loading state, error state, or the current AI_Marking_Suggestion
  content.

---

## Requirements

### Requirement 1: Requesting an AI Marking Suggestion

**User Story:** As a recruiter, I want to request an AI-generated marking
suggestion for an eligible candidate answer, so that I have a starting point
for evaluating it without the system marking it for me automatically.

#### Acceptance Criteria

1. WHEN a Marking_Recruiter opens a submission for review, THE
   Results_Component SHALL display, in the Suggestion_Panel of each
   Eligible_Question's Candidate_Answer within that submission that has no
   retrieved AI_Marking_Suggestion, a control to request an AI marking
   suggestion.
2. WHEN a Marking_Recruiter activates the request control for an
   Eligible_Question's Candidate_Answer, THE AI_Marking_Suggestion_Service
   SHALL send a request to generate an AI_Marking_Suggestion for that
   Candidate_Answer.
3. THE Results_Component SHALL NOT display a request control, loading
   state, error state, or suggestion content in the Suggestion_Panel of any
   question that is not an Eligible_Question.
4. WHEN a Marking_Recruiter activates the request control for a
   Candidate_Answer that already has a displayed AI_Marking_Suggestion, THE
   AI_Marking_Suggestion_Service SHALL send a request to regenerate the
   AI_Marking_Suggestion for that Candidate_Answer.
5. WHILE a request to generate or regenerate an AI_Marking_Suggestion for a
   Candidate_Answer is in progress, THE Results_Component SHALL disable
   that Candidate_Answer's request control so that activating it again does
   not send an additional generate or regenerate request until the
   in-progress request completes.
6. IF a Marking_Recruiter navigates away from a submission before a request
   to generate or regenerate an AI_Marking_Suggestion for that submission's
   Candidate_Answer completes, THEN THE Results_Component SHALL discard the
   resulting response when it arrives and SHALL NOT display its
   AI_Marking_Suggestion content, loading state, or error indication for
   that Candidate_Answer.
7. WHILE a request to generate or regenerate an AI_Marking_Suggestion is in
   progress for one Candidate_Answer, THE Results_Component SHALL keep the
   request control, loading state, and suggestion content of every other
   Candidate_Answer's Suggestion_Panel unaffected by that in-progress
   request.

---

### Requirement 2: Viewing AI Marking Suggestion Content

**User Story:** As a recruiter, I want to see the AI's suggested score and
rationale for an answer, so that I can understand the AI's reasoning before
deciding on the final mark.

#### Acceptance Criteria

1. WHEN a Marking_Recruiter selects a submission for review, THE
   AI_Marking_Suggestion_Service SHALL send, independently for each
   Eligible_Question's Candidate_Answer in that submission, a request to
   retrieve the current AI_Marking_Suggestion, if one exists, for that
   Candidate_Answer.
2. WHEN a retrieved or newly generated AI_Marking_Suggestion is available
   for a Candidate_Answer, THE Results_Component SHALL display, in that
   answer's Suggestion_Panel, the Suggested_Score together with the
   question's maximum score, the Suggested_Rationale, and the instant the
   AI_Marking_Suggestion was generated.
3. IF no AI_Marking_Suggestion is available yet for an Eligible_Question's
   Candidate_Answer, THEN THE Results_Component SHALL display the request
   control described in Requirement 1 in that answer's Suggestion_Panel
   instead of suggestion content.
4. THE Results_Component SHALL render each answer's Suggestion_Panel as a
   DOM element separate from, positioned outside of, and not overlapping
   that answer's Manual_Mark_Input element.
5. IF a request to retrieve the current AI_Marking_Suggestion for one
   Eligible_Question's Candidate_Answer within a submission fails, THEN THE
   Results_Component SHALL continue to retrieve and display
   AI_Marking_Suggestion content for that submission's other
   Eligible_Question Candidate_Answers, unaffected by that failure.

---

### Requirement 3: Loading State

**User Story:** As a recruiter, I want to see when an AI suggestion is being
generated or fetched, so that I understand the current state of the
Suggestion_Panel without losing access to manual marking.

#### Acceptance Criteria

1. WHILE a request to generate or retrieve an AI_Marking_Suggestion for a
   Candidate_Answer is in progress, THE Results_Component SHALL display a
   loading indication in that Candidate_Answer's Suggestion_Panel,
   independently of the loading state of any other Candidate_Answer's
   Suggestion_Panel.
2. WHILE a request to generate or retrieve an AI_Marking_Suggestion for a
   Candidate_Answer is in progress, THE Results_Component SHALL keep that
   Candidate_Answer's Manual_Mark_Input and its Answer_Score feedback input
   enabled and editable.
3. WHILE a request to generate or retrieve an AI_Marking_Suggestion for a
   Candidate_Answer is in progress, THE Results_Component SHALL NOT display
   an active request control for that Candidate_Answer, so that a
   Marking_Recruiter cannot trigger an additional AI_Marking_Suggestion
   request for that Candidate_Answer until the in-progress request
   completes.
4. WHEN a request to generate or retrieve an AI_Marking_Suggestion for a
   Candidate_Answer completes, whether that request succeeds or fails, THE
   Results_Component SHALL remove the loading indication from that
   Candidate_Answer's Suggestion_Panel.

---

### Requirement 4: Error Handling

**User Story:** As a recruiter, I want a failed AI suggestion request to
never block me from marking a candidate manually, so that AI provider
outages or ineligible questions never stop me from completing the review.

#### Acceptance Criteria

1. IF a request to generate or retrieve an AI_Marking_Suggestion for a
   Candidate_Answer fails, THEN THE Results_Component SHALL display an
   error indication in that Candidate_Answer's Suggestion_Panel and SHALL
   retain the previously displayed AI_Marking_Suggestion for that
   Candidate_Answer, if any, unchanged.
2. IF a request to generate or retrieve an AI_Marking_Suggestion for a
   Candidate_Answer fails, THEN THE Manual_Mark_Input and feedback input for
   that Candidate_Answer SHALL remain enabled and editable, and any
   previously entered values in them SHALL remain unchanged.
3. WHEN a Marking_Recruiter activates the request control again for a
   Candidate_Answer after a failed AI_Marking_Suggestion request, THE
   AI_Marking_Suggestion_Service SHALL send a new request to generate an
   AI_Marking_Suggestion for that Candidate_Answer.
4. IF a request to retrieve the current AI_Marking_Suggestion for a
   Candidate_Answer receives an HTTP 404 response, THEN THE
   Results_Component SHALL treat that Candidate_Answer as having no
   AI_Marking_Suggestion and SHALL display the request control described in
   Requirement 1, without displaying an error indication.
5. IF a request to generate or retrieve an AI_Marking_Suggestion for a
   Candidate_Answer receives an HTTP response with a status code in the
   4xx or 5xx range, other than 404, THEN THE Results_Component SHALL
   display an error indication in that Candidate_Answer's Suggestion_Panel,
   distinct from the "no suggestion yet" state described in Criterion 4.
6. THE Results_Component SHALL treat a request to generate or retrieve an
   AI_Marking_Suggestion for a Candidate_Answer as failed if the request
   does not receive an HTTP response, including due to a network error or
   timeout, or if the response received meets the status condition
   described in Criterion 5.

---

### Requirement 5: Applying a Suggested Score to Manual Marking

**User Story:** As a recruiter, I want to optionally use the AI's suggested
score as a starting point for my own mark, so that I can save time while
still reviewing and adjusting it before it counts as the final score.

#### Acceptance Criteria

1. WHILE an AI_Marking_Suggestion is displayed in a Candidate_Answer's
   Suggestion_Panel, including while a regeneration request for that
   Candidate_Answer is in progress, THE Results_Component SHALL display a
   control allowing the Marking_Recruiter to copy the Suggested_Score into
   that Candidate_Answer's Manual_Mark_Input.
2. WHEN a Marking_Recruiter activates the control described in Criterion 1,
   THE Results_Component SHALL set the Manual_Mark_Input's current editable
   value to the Suggested_Score, overwriting any value currently present in
   it whether entered manually or previously copied from a Suggested_Score,
   and SHALL NOT send any Answer_Score request to the backend as a result
   of that activation.
3. THE Results_Component SHALL NOT set a Manual_Mark_Input's value from a
   Suggested_Score except as a direct result of the Marking_Recruiter
   activating the control described in Criterion 1.
4. WHEN a Marking_Recruiter edits a Manual_Mark_Input's value after it was
   set from a Suggested_Score, THE Results_Component SHALL retain the
   edited value in that Manual_Mark_Input.
5. WHEN a Marking_Recruiter saves the mark for a Candidate_Answer whose
   Manual_Mark_Input value was edited after being set from a
   Suggested_Score, THE Results_Component SHALL submit the edited value,
   rather than the original Suggested_Score, as the score in that
   Candidate_Answer's Answer_Score submission.

---

### Requirement 6: Regeneration and Suggestion Display History

**User Story:** As a recruiter, I want regenerating a suggestion to replace
what I previously saw, so that I am never confused about which suggestion
is current.

#### Acceptance Criteria

1. WHEN a request to regenerate an AI_Marking_Suggestion for a
   Candidate_Answer completes successfully, THE Results_Component SHALL
   replace the previously displayed AI_Marking_Suggestion content in that
   answer's Suggestion_Panel with the newly returned AI_Marking_Suggestion.
2. THE Results_Component SHALL display, at any point in time, at most one
   AI_Marking_Suggestion in a Candidate_Answer's Suggestion_Panel.
3. THE Results_Component SHALL NOT provide, anywhere in a Candidate_Answer's
   answer card, a control, view, or display that exposes an
   AI_Marking_Suggestion that has been replaced by a subsequent successful
   regeneration for that Candidate_Answer; once replaced, a prior
   AI_Marking_Suggestion SHALL NOT remain accessible through the
   Results_Component.
4. WHILE a request to generate or regenerate an AI_Marking_Suggestion for a
   Candidate_Answer is in progress, THE Results_Component SHALL disable
   that Candidate_Answer's request control, such that no additional
   generate or regenerate request can be sent for that Candidate_Answer
   until the in-progress request completes.
5. IF a response for a generate or regenerate request for a
   Candidate_Answer arrives after a more recently sent generate or
   regenerate request for that same Candidate_Answer, THEN THE
   Results_Component SHALL discard the earlier response and SHALL NOT use
   it to update that Candidate_Answer's Suggestion_Panel.

---

### Requirement 7: Access Control

**User Story:** As a system operator, I want the AI marking suggestion
capability restricted the same way the rest of the marking view is, so that
candidates and unauthorized users cannot request or view AI-generated
marking data.

#### Acceptance Criteria

1. THE Results_Component SHALL expose the request control, Suggestion_Panel,
   and related AI marking suggestion controls only within its existing
   staff-only route, which is reachable only by an authenticated
   Marking_Recruiter session.
2. IF a request to generate or retrieve an AI_Marking_Suggestion for a
   Candidate_Answer receives an HTTP 401 or HTTP 403 response, THEN THE
   Results_Component SHALL display an access-denied error indication in
   that Candidate_Answer's Suggestion_Panel.
3. IF a request to generate or retrieve an AI_Marking_Suggestion for a
   Candidate_Answer receives an HTTP 401 or HTTP 403 response, THEN THE
   Results_Component SHALL NOT display any AI_Marking_Suggestion content
   for that Candidate_Answer.
4. IF a request to generate or retrieve an AI_Marking_Suggestion for a
   Candidate_Answer receives an HTTP 401 or HTTP 403 response, THEN THE
   Results_Component SHALL disable the request control for that
   Candidate_Answer until the page is reloaded or the Marking_Recruiter
   re-authenticates.

---

### Requirement 8: Ineligible Question and Missing Answer Handling

**User Story:** As a recruiter, I want a clear indication when a question or
answer isn't eligible for AI-assisted marking, so that I understand why no
suggestion can be generated.

#### Acceptance Criteria

1. IF a request to generate an AI_Marking_Suggestion receives an HTTP 400
   response, THEN THE Results_Component SHALL display, in that answer's
   Suggestion_Panel, an error indication explaining that the question or
   answer is not eligible for AI-assisted marking, and SHALL NOT display
   AI_Marking_Suggestion content for that Candidate_Answer.
2. WHERE a GROUP question has individually markable TEXT or CODE_SUBMISSION
   sub-questions, THE Results_Component SHALL treat each such
   sub-question's Candidate_Answer as independently eligible for AI marking
   suggestions, consistent with how each sub-question is already
   independently manually marked.
3. WHERE a question's type is `MCQ`, or a question's type is `GROUP` (with
   respect to the GROUP question itself, excluding its individually
   markable TEXT or CODE_SUBMISSION sub-questions addressed in Acceptance
   Criterion 2), THE Results_Component SHALL NOT display any AI marking
   suggestion request control, loading state, error state, or suggestion
   content for that question, regardless of whether it is auto-marked or
   manually overridden.
4. IF a question's type is `TEXT` or `CODE_SUBMISSION` and its
   Candidate_Answer content is null, empty, or consists only of whitespace,
   THEN THE Results_Component SHALL NOT display any AI marking suggestion
   request control, loading state, error state, or suggestion content for
   that Candidate_Answer.
