package com.e2eq.framework.service.access;

import com.e2eq.framework.model.security.AccessInvite;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.function.BiConsumer;

/**
 * Test-only notification service. Inert unless a test installs a {@link #onDelivery} hook, which
 * then runs for every notification (it may throw to simulate a failed delivery). It extends the
 * no-op service so the raw token is still returned to the inviter in tests, exactly as with no
 * delivery channel configured.
 */
@ApplicationScoped
public class ScriptableTestInviteNotificationService extends NoOpAccessInviteNotificationService {

    private volatile BiConsumer<AccessInvite, String> onDelivery;

    public void onDelivery(BiConsumer<AccessInvite, String> hook) {
        this.onDelivery = hook;
    }

    public void reset() {
        this.onDelivery = null;
    }

    @Override
    public void sendInviteNotification(String realm, AccessInvite invite, String rawToken) {
        BiConsumer<AccessInvite, String> hook = onDelivery;
        if (hook != null) {
            hook.accept(invite, rawToken);
        }
    }
}
