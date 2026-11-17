package com.e2eq.framework.rest.filters;

import io.vertx.ext.web.RoutingContext;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/** Adapts existing authority inputs without substituting token claims for provider validation. */
final class RequestRoleResolution {
    private RequestRoleResolution() { }

    static String[] delegated(RoutingContext request, String issuer, String subject, String userId,
                              Collection<String> tokenRoles, String realm, String application,
                              Supplier<String[]> resolver) {
        var key = new RequestAuthorizationState.RoleKey(issuer, subject, userId, realm, application, sorted(tokenRoles));
        return RequestAuthorizationState.resolve(request, key, resolver);
    }

    private static List<String> sorted(Collection<String> roles) {
        return roles == null ? List.of() : roles.stream().sorted(java.util.Comparator.nullsFirst(String::compareTo)).toList();
    }
}
