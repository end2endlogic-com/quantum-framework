package com.e2eq.framework.service.access;

import com.e2eq.framework.model.security.AccessInvite;

/**
 * Delivers an invite's raw token to the invitee. The {@code invite} passed in is a read-only
 * view: implementations must not save it (on resend it carries no version, so a save fails and
 * the resend reports {@code INVITE_NOTIFICATION_FAILED}). Throwing fails the delivery; on resend
 * the previous token then keeps working.
 */
public interface AccessInviteNotificationService {
    default void sendInviteNotification(String realm, AccessInvite invite, String rawToken) {
    }
}
