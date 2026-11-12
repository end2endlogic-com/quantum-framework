package com.e2eq.framework.security.principal;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * {@code quantum.auth.principal-source}: unset keeps today's principal construction unchanged;
 * {@code remote} resolves every verified bearer through the {@link RemotePrincipalSource}
 * ({@code IDENTITY_ONLY_TOKENS_DESIGN.md} section 3). The receiving application id is
 * {@code quantum.security.session.application-id}, else
 * {@code quantum.application-registration.application-id}.
 *
 * <p>Startup fails when {@code remote} is set without a source bean or an application id, when the
 * value is anything else, or together with an application-session realm selection
 * ({@code quantum.security.session.realm} or {@code quantum.security.session.selection-policy}),
 * which remote mode does not apply: Auth selects the realm from the request's {@code X-Realm} or
 * the identity's home realm.
 */
@ApplicationScoped
public class RemotePrincipalSettings {

    public static final String PROPERTY = "quantum.auth.principal-source";
    public static final String REMOTE = "remote";

    @ConfigProperty(name = PROPERTY)
    Optional<String> principalSource = Optional.empty();

    @ConfigProperty(name = "quantum.security.session.application-id")
    Optional<String> sessionApplicationId = Optional.empty();

    @ConfigProperty(name = "quantum.application-registration.application-id")
    Optional<String> registrationApplicationId = Optional.empty();

    @ConfigProperty(name = "quantum.security.session.realm")
    Optional<String> sessionRealm = Optional.empty();

    @ConfigProperty(name = "quantum.security.session.selection-policy")
    Optional<String> sessionSelectionPolicy = Optional.empty();

    @Inject
    Instance<RemotePrincipalSource> sources;

    void onStart(@Observes StartupEvent event) {
        validate();
    }

    /** Fails with the reason when the configuration cannot run; does nothing when unset. */
    public void validate() {
        String mode = configured(principalSource);
        if (mode == null) {
            return;
        }
        if (!REMOTE.equals(mode)) {
            throw new IllegalStateException(PROPERTY + " must be '" + REMOTE + "' or unset, was '" + mode + "'");
        }
        if (sources == null || sources.isUnsatisfied()) {
            throw new IllegalStateException(PROPERTY + "=remote needs a RemotePrincipalSource bean "
                    + "(quantum-auth-client-provider with auth.provider=quantum-auth-service); none is deployed");
        }
        if (sources.isAmbiguous()) {
            throw new IllegalStateException(PROPERTY + "=remote found more than one RemotePrincipalSource bean");
        }
        if (applicationId() == null) {
            throw new IllegalStateException(PROPERTY + "=remote needs this application's id: set "
                    + "quantum.security.session.application-id or quantum.application-registration.application-id");
        }
        if (configured(sessionRealm) != null || configured(sessionSelectionPolicy) != null) {
            throw new IllegalStateException(PROPERTY + "=remote does not apply quantum.security.session.realm or "
                    + "quantum.security.session.selection-policy: Auth selects the realm; remove them");
        }
    }

    /** True when {@code quantum.auth.principal-source=remote}. */
    public boolean remote() {
        return REMOTE.equals(configured(principalSource));
    }

    /** The receiving application id sent to Auth, or null when none is configured. */
    public String applicationId() {
        String session = configured(sessionApplicationId);
        return session != null ? session : configured(registrationApplicationId);
    }

    public RemotePrincipalSource source() {
        return sources.get();
    }

    private static String configured(Optional<String> value) {
        return value == null ? null : value.map(String::trim).filter(v -> !v.isEmpty()).orElse(null);
    }
}
