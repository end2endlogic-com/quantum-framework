package com.e2eq.framework.model.persistent.migration.refname;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CanonicalRefNameMigratorTest {

   @Test
   void upperSnakesEveryRunOfOtherCharacters() {
      assertEquals("DIGITAL_WORKER_OPERATOR", CanonicalRefNameMigrator.canonicalForm("digital-worker-operator"));
      assertEquals("HELIXOR_CODE_ADMIN_FULL", CanonicalRefNameMigrator.canonicalForm("helixor-code-admin-full"));
      assertEquals("PLATFORM_SYSTEM_ADMIN", CanonicalRefNameMigrator.canonicalForm("platform.system--admin"));
      assertEquals("OPS_TEAM", CanonicalRefNameMigrator.canonicalForm("-ops team_"));
      assertEquals("ADMIN", CanonicalRefNameMigrator.canonicalForm("admin"));
      assertEquals("ALREADY_OK", CanonicalRefNameMigrator.canonicalForm("ALREADY_OK"));
   }

   @Test
   void hasNoCanonicalFormWhenTheRuleCannotBeMet() {
      assertNull(CanonicalRefNameMigrator.canonicalForm("9-lives"));
      assertNull(CanonicalRefNameMigrator.canonicalForm("ab"));
      assertNull(CanonicalRefNameMigrator.canonicalForm("--"));
      assertNull(CanonicalRefNameMigrator.canonicalForm("x".repeat(65)));
      assertNull(CanonicalRefNameMigrator.canonicalForm("507f1f77bcf86cd799439011"));
      assertNull(CanonicalRefNameMigrator.canonicalForm("abcdef0123456789abcd"));
      assertNull(CanonicalRefNameMigrator.canonicalForm(null));
   }
}
