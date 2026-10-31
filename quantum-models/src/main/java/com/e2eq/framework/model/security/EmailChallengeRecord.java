package com.e2eq.framework.model.security;

import com.e2eq.framework.model.auth.EmailChallenge;
import com.e2eq.framework.model.persistent.base.BaseModel;
import dev.morphia.annotations.Entity;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** Authority-only challenge storage; never exposed by a generic CRUD resource. */
@Entity("emailChallenge")
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class EmailChallengeRecord extends BaseModel {
    private String challengeId;
    private String email;
    private String applicationId;
    private EmailChallenge.Purpose purpose;
    private String secretDigest;
    private String clientBindingDigest;
    private Instant expiresAt;
    private int attempts;
    private int maxAttempts;
    private EmailChallenge.State state;
    private long revision;

    public EmailChallenge snapshot() {
        return new EmailChallenge(challengeId, email, applicationId, purpose, secretDigest,
                clientBindingDigest, expiresAt, attempts, maxAttempts, state, revision);
    }
    public static EmailChallengeRecord from(EmailChallenge challenge) {
        var record = new EmailChallengeRecord();
        record.setRefName(challenge.id());
        record.challengeId = challenge.id();
        record.email = challenge.email();
        record.applicationId = challenge.applicationId();
        record.purpose = challenge.purpose();
        record.secretDigest = challenge.secretDigest();
        record.clientBindingDigest = challenge.clientBindingDigest();
        record.expiresAt = challenge.expiresAt();
        record.attempts = challenge.attempts();
        record.maxAttempts = challenge.maxAttempts();
        record.state = challenge.state();
        record.revision = challenge.revision();
        return record;
    }
    @Override public String bmFunctionalArea() { return "SECURITY"; }
    @Override public String bmFunctionalDomain() { return "EMAIL_CHALLENGES"; }
    @Override public String toString() { return "EmailChallengeRecord[redacted]"; }
}
