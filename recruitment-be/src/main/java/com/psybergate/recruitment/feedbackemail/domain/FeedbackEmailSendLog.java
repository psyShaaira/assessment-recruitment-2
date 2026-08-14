package com.psybergate.recruitment.feedbackemail.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "feedback_email_send_log")
@Getter
@Setter
@NoArgsConstructor
public class FeedbackEmailSendLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "submission_id", nullable = false)
    private UUID submissionId;

    @CreationTimestamp
    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    @Column(name = "sent_by")
    private UUID sentBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private FeedbackEmailSendStatus status;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;
}
