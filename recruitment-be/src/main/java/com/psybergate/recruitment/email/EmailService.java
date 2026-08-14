package com.psybergate.recruitment.email;

import com.psybergate.recruitment.domain.Assessment;
import com.psybergate.recruitment.domain.Candidate;

import java.time.Instant;

public interface EmailService {
    void sendInvitation(Candidate candidate, Assessment assessment, String invitationLink, Instant expiresAt, String plainPassword);
    void sendReminder(Candidate candidate, Assessment assessment, Instant expiresAt, String invitationLink);
    void sendCancellation(Candidate candidate, Assessment assessment);
    void sendContactMessage(Candidate candidate, String subject, String message);
    void sendFeedbackReport(Candidate candidate, String plainTextBody);
}
