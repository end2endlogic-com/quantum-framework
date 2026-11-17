package com.e2eq.framework.rest.filters;

import com.e2eq.framework.security.session.SessionSelectionException;
import com.e2eq.framework.security.session.SessionSelectionPolicy;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.List;
import java.util.Optional;

import static com.e2eq.framework.security.session.SessionSelectionException.Code.*;

/** Selects only a realm proposal. All authorization stays in the existing filter. */
@ApplicationScoped
public class SessionSelectionCoordinator {
    @Inject Instance<SessionSelectionPolicy> policies;
    @ConfigProperty(name = "quantum.security.session.selection-policy")
    Optional<String> policyId = Optional.empty();
    @ConfigProperty(name = "quantum.security.session.selection-policy-version")
    Optional<String> policyVersion = Optional.empty();
    @ConfigProperty(name = "quantum.security.session.application-id")
    Optional<String> applicationId = Optional.empty();
    private SessionSelectionPolicy selected;

    void start(@Observes StartupEvent event) {
        if (policyId.isEmpty() && policyVersion.isEmpty()) {
            selected = null;
            return; // Unselected extensions must not be instantiated or evaluated.
        }
        selected = selectPolicy(policyId.orElse(null), policyVersion.orElse(null),
                policies.stream().toList());
    }

    static SessionSelectionPolicy selectPolicy(String id, String version, List<SessionSelectionPolicy> providers) {
        if (id == null && version == null) return null;
        if (id == null || id.isBlank() || version == null || version.isBlank()) {
            throw new SessionSelectionException(INCOMPLETE_CONFIGURATION,
                    "Set both selection-policy and selection-policy-version, or omit both");
        }
        var matches = providers.stream().filter(p -> id.equals(p.id())).toList();
        if (matches.isEmpty()) {
            String available = providers.stream().map(SessionSelectionPolicy::id).sorted()
                    .collect(java.util.stream.Collectors.joining(", "));
            throw new SessionSelectionException(MISSING_POLICY, "Selected policy is not installed; available ids: " + available);
        }
        if (matches.size() != 1) throw new SessionSelectionException(DUPLICATE_POLICY,
                "Install exactly one implementation of the selected policy id");
        SessionSelectionPolicy policy = matches.get(0);
        if (!version.equals(policy.version())) throw new SessionSelectionException(VERSION_MISMATCH,
                "Installed policy version does not match selection-policy-version");
        return policy;
    }

    boolean enabled() { return selected != null; }

    String realm(RoutingContext routing, SecurityIdentity identity, String requested, String configured) {
        boolean user = identity != null && !identity.isAnonymous();
        JsonWebToken token = user && identity.getPrincipal() instanceof JsonWebToken jwt ? jwt : null;
        if (token != null && com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders.SERVICE_TOKEN_TYPE
                .equalsIgnoreCase(claim(token, com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders.TOKEN_TYPE_CLAIM))) {
            user = false;
        }
        if (selected == null || !user) return SecurityFilter.sessionRealmSelection(requested, configured, user);
        var input = new SessionSelectionPolicy.Input(claim(token, "iss"),
                token == null ? identity.getPrincipal().getName() : token.getSubject(), claim(token, "realm"),
                requested, configured, applicationId.map(String::trim).filter(s -> !s.isEmpty()).orElse(claim(token, "azp")));
        var state = RequestAuthorizationState.from(routing);
        return (state == null ? evaluate(input) : state.selectRealm(input, () -> evaluate(input))).realm();
    }

    private SessionSelectionPolicy.Selection evaluate(SessionSelectionPolicy.Input input) {
        final SessionSelectionPolicy.Selection result;
        try { result = selected.select(input); }
        catch (RuntimeException failure) {
            // Do not expose extension exception messages that may contain credentials or request data.
            throw new SessionSelectionException(POLICY_FAILED, "Selected policy failed; request refused");
        }
        if (result == null || (result.realm() != null && result.realm().isBlank())) {
            throw new SessionSelectionException(INVALID_RESULT, "Policy must return a selection with null or nonblank realm");
        }
        return result;
    }

    private static String claim(JsonWebToken token, String name) {
        Object value = token == null ? null : token.getClaim(name);
        return value == null ? null : value.toString();
    }
}
