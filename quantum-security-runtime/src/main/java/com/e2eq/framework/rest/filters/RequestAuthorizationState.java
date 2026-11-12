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
    private final Map<com.e2eq.framework.security.principal.RemotePrincipalRequest.CacheKey, Object> remotePrincipals =
            new HashMap<>();

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

    /**
     * This request's remote principal resolution for {@code request}, asked of {@code source} once:
     * the roles augmentor and SecurityFilter share one answer, refusals included.
     */
    static com.e2eq.framework.model.securityrules.PrincipalContext remotePrincipal(
            RoutingContext routing,
            com.e2eq.framework.security.principal.RemotePrincipalRequest request,
            com.e2eq.framework.security.principal.RemotePrincipalSource source) {
        RequestAuthorizationState state = from(routing);
        return state == null ? requireResolved(source.resolve(request))
                : state.remotePrincipal(request.cacheKey(), () -> requireResolved(source.resolve(request)));
    }

    private synchronized com.e2eq.framework.model.securityrules.PrincipalContext remotePrincipal(
            com.e2eq.framework.security.principal.RemotePrincipalRequest.CacheKey key,
            Supplier<com.e2eq.framework.model.securityrules.PrincipalContext> resolver) {
        Object outcome = remotePrincipals.get(key);
        if (outcome == null) {
            try {
                outcome = resolver.get();
            } catch (RuntimeException refused) {
                outcome = refused;
            }
            remotePrincipals.put(key, outcome);
        }
        if (outcome instanceof RuntimeException refused) throw refused;
        return (com.e2eq.framework.model.securityrules.PrincipalContext) outcome;
    }

    private static com.e2eq.framework.model.securityrules.PrincipalContext requireResolved(
            com.e2eq.framework.model.securityrules.PrincipalContext context) {
        if (context == null) {
            throw com.e2eq.framework.security.principal.RemotePrincipalResolutionException.unavailable(
                    "The remote principal source returned no principal");
        }
        return context;
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
