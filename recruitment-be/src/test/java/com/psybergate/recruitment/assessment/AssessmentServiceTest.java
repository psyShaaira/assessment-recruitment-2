package com.psybergate.recruitment.assessment;

import com.psybergate.recruitment.assessment.dto.AddAssessmentQuestionRequest;
import com.psybergate.recruitment.assessment.dto.AssemblyQuotaDto;
import com.psybergate.recruitment.assessment.dto.AssemblyQuotaOutcome;
import com.psybergate.recruitment.assessment.dto.AssemblySuggestionRequest;
import com.psybergate.recruitment.assessment.dto.AssemblySuggestionResponse;
import com.psybergate.recruitment.domain.*;
import com.psybergate.recruitment.question.domain.TextQuestion;
import com.psybergate.recruitment.repository.AssessmentQuestionRepository;
import com.psybergate.recruitment.repository.AssessmentRepository;
import com.psybergate.recruitment.repository.QuestionRepository;
import com.psybergate.recruitment.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssessmentServiceTest {

    @Mock private AssessmentRepository assessmentRepository;
    @Mock private AssessmentQuestionRepository assessmentQuestionRepository;
    @Mock private QuestionRepository questionRepository;
    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AssessmentServiceImpl service;

    private UUID assessmentId;
    private Assessment assessment;

    @BeforeEach
    void setUp() {
        assessmentId = UUID.randomUUID();
        assessment = new Assessment();
        assessment.setId(assessmentId);
        assessment.setTitle("Test Assessment");
        assessment.setQuestions(new java.util.ArrayList<>());

        lenient().when(assessmentRepository.findById(assessmentId)).thenReturn(Optional.of(assessment));
    }

    @Test
    void addQuestion_standaloneQuestionAlreadyAGroupMemberOnThisAssessment_returns409() {
        UUID sharedQuestionId = UUID.randomUUID();
        TextQuestion sharedQuestion = new TextQuestion();
        sharedQuestion.setId(sharedQuestionId);
        sharedQuestion.setMaxScore(5);

        GroupQuestionMember member = new GroupQuestionMember();
        member.setQuestion(sharedQuestion);

        GroupQuestion existingGroup = new GroupQuestion();
        existingGroup.setId(UUID.randomUUID());
        existingGroup.setMembers(List.of(member));

        AssessmentQuestion existingGroupAq = new AssessmentQuestion();
        existingGroupAq.setQuestion(existingGroup);
        existingGroupAq.setDisplayOrder(1);

        when(questionRepository.findById(sharedQuestionId)).thenReturn(Optional.of(sharedQuestion));
        when(assessmentQuestionRepository.findByAssessmentIdAndQuestionId(assessmentId, sharedQuestionId))
                .thenReturn(Optional.empty());
        when(assessmentQuestionRepository.findByAssessmentIdOrderByDisplayOrder(assessmentId))
                .thenReturn(List.of(existingGroupAq));

        assertThatThrownBy(() -> service.addQuestion(assessmentId,
                new AddAssessmentQuestionRequest(sharedQuestionId, 2)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void addQuestion_groupWhoseMembersAreAlreadyStandaloneOnThisAssessment_returns409() {
        UUID sharedQuestionId = UUID.randomUUID();
        TextQuestion sharedQuestion = new TextQuestion();
        sharedQuestion.setId(sharedQuestionId);
        sharedQuestion.setMaxScore(5);

        GroupQuestionMember member = new GroupQuestionMember();
        member.setQuestion(sharedQuestion);

        GroupQuestion newGroup = new GroupQuestion();
        UUID groupId = UUID.randomUUID();
        newGroup.setId(groupId);
        newGroup.setMembers(List.of(member));

        AssessmentQuestion existingStandaloneAq = new AssessmentQuestion();
        existingStandaloneAq.setQuestion(sharedQuestion);
        existingStandaloneAq.setDisplayOrder(1);

        when(questionRepository.findById(groupId)).thenReturn(Optional.of(newGroup));
        when(assessmentQuestionRepository.findByAssessmentIdAndQuestionId(assessmentId, groupId))
                .thenReturn(Optional.empty());
        when(assessmentQuestionRepository.findByAssessmentIdOrderByDisplayOrder(assessmentId))
                .thenReturn(List.of(existingStandaloneAq));

        assertThatThrownBy(() -> service.addQuestion(assessmentId,
                new AddAssessmentQuestionRequest(groupId, 2)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void addQuestion_noOverlap_succeeds() {
        UUID questionId = UUID.randomUUID();
        TextQuestion question = new TextQuestion();
        question.setId(questionId);
        question.setMaxScore(5);

        when(questionRepository.findById(questionId)).thenReturn(Optional.of(question));
        when(assessmentQuestionRepository.findByAssessmentIdAndQuestionId(assessmentId, questionId))
                .thenReturn(Optional.empty());
        when(assessmentQuestionRepository.findByAssessmentIdOrderByDisplayOrder(assessmentId))
                .thenReturn(List.of());
        lenient().when(assessmentQuestionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.addQuestion(assessmentId, new AddAssessmentQuestionRequest(questionId, 1));

        assertThat(result.created()).isTrue();
    }

    @Test
    void suggestQuestions_poolHasEnough_returnsFullQuotaWithNoShortfall() {
        TextQuestion q1 = textQuestion(Difficulty.EASY);
        TextQuestion q2 = textQuestion(Difficulty.EASY);
        TextQuestion q3 = textQuestion(Difficulty.EASY);
        when(questionRepository.findByTagNameAndDifficulty("java", Difficulty.EASY))
                .thenReturn(List.of(q1, q2, q3));

        AssemblyQuotaDto quota = new AssemblyQuotaDto("java", Difficulty.EASY, 2);
        AssemblySuggestionResponse response = service.suggestQuestions(new AssemblySuggestionRequest(List.of(quota)));

        AssemblyQuotaOutcome outcome = response.outcomes().get(0);
        assertThat(outcome.suggested()).hasSize(2);
        assertThat(outcome.shortfall()).isZero();
    }

    @Test
    void suggestQuestions_poolInsufficient_returnsShortfallInsteadOfError() {
        TextQuestion onlyMatch = textQuestion(Difficulty.HARD);
        when(questionRepository.findByTagNameAndDifficulty("rare-topic", Difficulty.HARD))
                .thenReturn(List.of(onlyMatch));

        AssemblyQuotaDto quota = new AssemblyQuotaDto("rare-topic", Difficulty.HARD, 5);
        AssemblySuggestionResponse response = service.suggestQuestions(new AssemblySuggestionRequest(List.of(quota)));

        AssemblyQuotaOutcome outcome = response.outcomes().get(0);
        assertThat(outcome.suggested()).hasSize(1);
        assertThat(outcome.shortfall()).isEqualTo(4);
    }

    @Test
    void suggestQuestions_multipleQuotasShareCandidatePool_neverSuggestsSameQuestionTwice() {
        TextQuestion q1 = textQuestion(Difficulty.MEDIUM);
        TextQuestion q2 = textQuestion(Difficulty.MEDIUM);
        // Both quotas resolve to the same 2-question pool (e.g. overlapping tags)
        when(questionRepository.findByTagNameAndDifficulty("sql", Difficulty.MEDIUM))
                .thenReturn(List.of(q1, q2));
        when(questionRepository.findByTagNameAndDifficulty("database", Difficulty.MEDIUM))
                .thenReturn(List.of(q1, q2));

        AssemblyQuotaDto quotaA = new AssemblyQuotaDto("sql", Difficulty.MEDIUM, 1);
        AssemblyQuotaDto quotaB = new AssemblyQuotaDto("database", Difficulty.MEDIUM, 1);
        AssemblySuggestionResponse response = service.suggestQuestions(
                new AssemblySuggestionRequest(List.of(quotaA, quotaB)));

        UUID firstPicked = response.outcomes().get(0).suggested().get(0).id();
        UUID secondPicked = response.outcomes().get(1).suggested().get(0).id();
        assertThat(secondPicked).isNotEqualTo(firstPicked);
    }

    private TextQuestion textQuestion(Difficulty difficulty) {
        TextQuestion q = new TextQuestion();
        q.setId(UUID.randomUUID());
        q.setTitle("Question " + q.getId());
        q.setDifficulty(difficulty);
        q.setTags(new java.util.HashSet<>());
        return q;
    }
}
