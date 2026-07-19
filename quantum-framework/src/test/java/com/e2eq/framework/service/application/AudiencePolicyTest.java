package com.e2eq.framework.service.application;

import com.e2eq.framework.service.application.AudiencePolicy.Decision;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AudiencePolicyTest {

   @Test
   void disabledWhenNoExpectedAudience() {
      assertEquals(Decision.ALLOW, AudiencePolicy.evaluate(Optional.empty(), true, Set.of("other")));
      assertEquals(Decision.ALLOW, AudiencePolicy.evaluate(Optional.of("  "), true, Set.of("other")));
      assertEquals(Decision.ALLOW, AudiencePolicy.evaluate(null, true, null));
   }

   @Test
   void legacyTokenWithoutAudAllowedUnlessRequired() {
      assertEquals(Decision.ALLOW,
              AudiencePolicy.evaluate(Optional.of("helixor-di"), false, null));
      assertEquals(Decision.ALLOW,
              AudiencePolicy.evaluate(Optional.of("helixor-di"), false, Set.of()));
      assertEquals(Decision.REJECT_MISSING_AUDIENCE,
              AudiencePolicy.evaluate(Optional.of("helixor-di"), true, Set.of()));
   }

   @Test
   void wrongAudienceAlwaysRejected() {
      assertEquals(Decision.REJECT_WRONG_AUDIENCE,
              AudiencePolicy.evaluate(Optional.of("helixor-di"), false, Set.of("helixor-scheduler")));
      assertEquals(Decision.REJECT_WRONG_AUDIENCE,
              AudiencePolicy.evaluate(Optional.of("helixor-di"), true, Set.of("helixor-scheduler")));
   }

   @Test
   void multiAudienceTokenMatchesWhenItNamesThisApp() {
      assertEquals(Decision.ALLOW, AudiencePolicy.evaluate(
              Optional.of("helixor-di"), true, Set.of("helixor-scheduler", "helixor-di")));
   }

   @Test
   void expectedAudienceIsTrimmed() {
      assertEquals(Decision.ALLOW, AudiencePolicy.evaluate(
              Optional.of(" helixor-di "), true, Set.of("helixor-di")));
   }
}
