package com.e2eq.framework.service.access;

import com.e2eq.framework.model.security.AccessInvite;

public interface AccessInviteNotificationService {
    default void sendInviteNotification(String realm, AccessInvite invite, String rawToken) {
    }
}
