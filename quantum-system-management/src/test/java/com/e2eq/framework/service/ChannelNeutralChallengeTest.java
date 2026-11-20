package com.e2eq.framework.service;

import com.e2eq.framework.model.auth.EmailChallenge;
import com.e2eq.framework.model.messaging.MessageChannel;
import com.e2eq.framework.model.security.EmailChallengeRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** Challenge records stay compatible: records stored before channels read back as email challenges. */
class ChannelNeutralChallengeTest {
    private static final Instant EXPIRES = Instant.parse("2026-11-20T12:10:00Z");

    @Test
    void legacyConstructorIsAnEmailChallengeToItsAddress() {
        var challenge = new EmailChallenge("c1", "user@example.test", "app", EmailChallenge.Purpose.PORTAL_SIGN_IN,
                "digest", null, EXPIRES, 0, 5, EmailChallenge.State.PENDING, 0);
        assertEquals(MessageChannel.EMAIL, challenge.channel());
        assertEquals("user@example.test", challenge.recipient());
        assertNull(challenge.redirect());
    }

    @Test
    void transitionKeepsChannelRecipientAndRedirect() {
        var challenge = new EmailChallenge("c2", null, "app", EmailChallenge.Purpose.PORTAL_SIGN_IN, "digest", null,
                EXPIRES, 0, 5, EmailChallenge.State.PENDING_DELIVERY, 0, MessageChannel.SMS, "+15550100", "/orders/42");
        var next = challenge.transition(EmailChallenge.State.PENDING, 1);
        assertEquals(MessageChannel.SMS, next.channel());
        assertEquals("+15550100", next.recipient());
        assertEquals("/orders/42", next.redirect());
        assertNull(next.email());
        assertEquals(1, next.revision());
        assertFalse(next.toString().contains("+15550100"));
        assertFalse(next.toString().contains("/orders"));
    }

    @Test
    void channelInvariantsAreEnforced() {
        assertThrows(IllegalArgumentException.class, () -> new EmailChallenge("c3", null, "app",
                EmailChallenge.Purpose.PORTAL_SIGN_IN, "d", null, EXPIRES, 0, 5, EmailChallenge.State.PENDING, 0,
                MessageChannel.SMS, " ", null), "a non-email challenge needs a recipient");
        assertThrows(IllegalArgumentException.class, () -> new EmailChallenge("c4", "a@example.test", "app",
                EmailChallenge.Purpose.PORTAL_SIGN_IN, "d", null, EXPIRES, 0, 5, EmailChallenge.State.PENDING, 0,
                MessageChannel.EMAIL, "b@example.test", null), "an email challenge's recipient is its address");
        assertThrows(IllegalArgumentException.class, () -> new EmailChallenge("c5", "a@example.test", "app",
                EmailChallenge.Purpose.PORTAL_SIGN_IN, "d", null, EXPIRES, 0, 5, EmailChallenge.State.PENDING, 0,
                MessageChannel.SMS, "+15550100", null), "only an email challenge carries email");
        assertThrows(IllegalArgumentException.class, () -> new EmailChallenge("c6", null, "app",
                EmailChallenge.Purpose.PORTAL_SIGN_IN, "d", null, EXPIRES, 0, 5, EmailChallenge.State.PENDING, 0),
                "an email challenge still requires email");
        var blankRedirect = new EmailChallenge("c7", "a@example.test", "app", EmailChallenge.Purpose.PORTAL_SIGN_IN,
                "d", null, EXPIRES, 0, 5, EmailChallenge.State.PENDING, 0, null, null, " ");
        assertNull(blankRedirect.redirect());
        assertEquals(MessageChannel.EMAIL, blankRedirect.channel());
    }

    @Test
    void recordStoredBeforeChannelsReadsBackAsEmail() {
        var stored = new EmailChallengeRecord();
        stored.setChallengeId("legacy");
        stored.setEmail("user@example.test");
        stored.setApplicationId("app");
        stored.setPurpose(EmailChallenge.Purpose.PASSWORD_SETUP);
        stored.setSecretDigest("digest");
        stored.setExpiresAt(EXPIRES);
        stored.setMaxAttempts(5);
        stored.setState(EmailChallenge.State.PENDING);

        var snapshot = stored.snapshot();
        assertEquals(MessageChannel.EMAIL, snapshot.channel());
        assertEquals("user@example.test", snapshot.recipient());
        assertEquals("user@example.test", snapshot.email());
        assertNull(snapshot.redirect());
    }

    @Test
    void recordRoundTripKeepsChannelFields() {
        var challenge = new EmailChallenge("c8", null, "app", EmailChallenge.Purpose.PORTAL_SIGN_IN, "d", null,
                EXPIRES, 0, 5, EmailChallenge.State.PENDING, 0, MessageChannel.WHATSAPP, "+447700900000", "/home");
        var record = EmailChallengeRecord.from(challenge);
        assertNull(record.getEmail());
        assertEquals(MessageChannel.WHATSAPP, record.getChannel());
        assertEquals(challenge, record.snapshot());

        var email = new EmailChallenge("c9", "user@example.test", "app", EmailChallenge.Purpose.PORTAL_SIGN_IN, "d",
                null, EXPIRES, 0, 5, EmailChallenge.State.PENDING, 0);
        var emailRecord = EmailChallengeRecord.from(email);
        assertEquals("user@example.test", emailRecord.getEmail(), "email challenges keep writing email for old readers");
        assertEquals(email, emailRecord.snapshot());
    }
}
