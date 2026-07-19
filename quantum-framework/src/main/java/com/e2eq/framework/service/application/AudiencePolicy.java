package com.e2eq.framework.service.application;

import java.util.Optional;
import java.util.Set;

/**
 * Application-audience admission policy for validated JWTs (migration
 * invariant: a token WITHOUT an {@code aud} claim is legacy-allowed unless the
 * deployment opts into strict mode; a token WITH an {@code aud} claim that does
 * not name this application is always rejected — no silent fallback).
 *
 * Pure logic so the decision table is unit-testable without container
 * scaffolding; {@code SecurityFilter} owns the wiring.
 */
public final class AudiencePolicy {

   public enum Decision {
      /** No expected audience configured, or the token satisfies the policy. */
      ALLOW,
      /** Token carries no aud claim and the deployment requires one. */
      REJECT_MISSING_AUDIENCE,
      /** Token carries an aud claim that does not include this application. */
      REJECT_WRONG_AUDIENCE
   }

   private AudiencePolicy() {
   }

   /**
    * @param expectedAudience this application's id (empty → policy disabled)
    * @param audienceRequired reject tokens with no aud claim (strict mode)
    * @param tokenAudience    the validated token's aud claim (null/empty → absent)
    */
   public static Decision evaluate(Optional<String> expectedAudience,
                                   boolean audienceRequired,
                                   Set<String> tokenAudience) {
      if (expectedAudience == null || expectedAudience.isEmpty()
              || expectedAudience.get().isBlank()) {
         return Decision.ALLOW;
      }
      if (tokenAudience == null || tokenAudience.isEmpty()) {
         return audienceRequired ? Decision.REJECT_MISSING_AUDIENCE : Decision.ALLOW;
      }
      return tokenAudience.contains(expectedAudience.get().trim())
              ? Decision.ALLOW
              : Decision.REJECT_WRONG_AUDIENCE;
   }
}
