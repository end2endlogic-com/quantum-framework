package com.e2eq.framework.rest.models;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Set;

/**
 * Server-truth result of evaluating a user's application access for a realm —
 * the SAME {@code ApplicationAuthorizationResolver} decision a login would
 * make (list-or-* contract), computed from the stored credential pattern and
 * the (user, realm) membership grant without authenticating.
 */
@RegisterForReflection
public record ApplicationAccessEvaluation(
        String userId,
        String realmRefName,
        String requestedApplicationId,
        /** LEGACY | RESOLVED | AMBIGUOUS | DENIED */
        String outcome,
        /** Token aud set a login would mint (RESOLVED only; may be ["*"]). */
        Set<String> audiences,
        /** azp a login would mint (RESOLVED; may be null). */
        String activeApplication,
        boolean wildcard,
        /** Selection candidates (AMBIGUOUS; registry-enriched for patterns). */
        List<String> candidates,
        String deniedApplication,
        /** Inputs the decision was made from, for admin transparency. */
        String applicationRegEx,
        List<String> authorizedApplications,
        String defaultApplication,
        boolean membershipExists) {
}
