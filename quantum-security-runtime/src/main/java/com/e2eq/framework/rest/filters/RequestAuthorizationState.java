package com.e2eq.framework.rest.filters;

import io.vertx.ext.web.RoutingContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Request-owned work shared across authentication and REST; never a permission cache. */
final class RequestAuthorizationState {
    private static final String KEY = RequestAuthorizationState.class.getName();
    private final Map<RoleKey, List<String>> roles = new HashMap<>();
    private ResolvedAuthorizationContext completed;
    private final Map<com.e2eq.framework.security.session.SessionSelectionPolicy.Input,
            com.e2eq.framework.security.session.SessionSelectionPolicy.Selection> selections = new HashMap<>();

    synchronized com.e2eq.framework.security.session.SessionSelectionPolicy.Selection selectRealm(
            com.e2eq.framework.security.session.SessionSelectionPolicy.Input input,
            Supplier<com.e2eq.framework.security.session.SessionSelectionPolicy.Selection> resolver) {
        return selections.computeIfAbsent(input, ignored -> resolver.get());
    }


    static RequestAuthorizationState from(RoutingContext request) {
        if (request == null) return null; // Non-HTTP callers retain uncached resolution.
        Map<String, Object> data = request.data();
        synchronized (data) {
            Object existing = data.get(KEY);
            if (existing instanceof RequestAuthorizationState state) return state;
            if (existing != null) throw new IllegalStateException("Invalid request authorization state");
            var state = new RequestAuthorizationState();
            data.put(KEY, state);
            return state;
        }
    }

    static String[] resolve(RoutingContext request, RoleKey key, Supplier<String[]> resolver) {
        RequestAuthorizationState state = from(request);
        return state == null ? resolver.get() : state.resolve(key, resolver);
    }

    private synchronized String[] resolve(RoleKey key, Supplier<String[]> resolver) {
        List<String> result = roles.get(key);
        if (result == null) {
            String[] resolved = resolver.get();
            if (resolved == null) throw new IllegalStateException("Role authority returned no result");
            result = Collections.unmodifiableList(new ArrayList<>(Arrays.asList(resolved)));
            roles.put(key, result);
        }
        return result.toArray(String[]::new);
    }

    synchronized void beginAdmission() { completed = null; }
    synchronized void complete(ResolvedAuthorizationContext context) { completed = context; }
    synchronized ResolvedAuthorizationContext completed() { return completed; }

    /** Exact inputs only. Mismatched issuer, principal, realm, application or token roles re-resolve. */
    record RoleKey(String issuer, String subject, String userId,
                   String effectiveRealm, String applicationId, List<String> tokenRoles) {
        RoleKey {
            tokenRoles = copy(tokenRoles);
        }
        private static List<String> copy(List<String> roles) {
            return Collections.unmodifiableList(new ArrayList<>(roles));
        }
    }
}
