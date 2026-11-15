package com.e2eq.framework.model.security;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalRoleReservationTest {

   @Test
   void legacyTenArgConstructorPreservesBinaryCompatibilityAndLeavesExternalTenantsNull() {
      UserRealmRole legacy = new UserRealmRole(
          "alice@example.com",
          "sub-alice",
          "acme-realm",
          List.of("user"),
          List.of("files"),
          "files",
          List.of("acme"),
          null,
          "acme-org",
          UserRealmRole.STATUS_ACTIVE);

      assertNull(legacy.getExternalTenantIds());
      assertFalse(legacy.externalInTenant("acme"));
      assertDoesNotThrow(legacy::validate);
   }

   @Test
   void externalTenantIdsDistinguishesMemberAndOutsiderTenantsOnOneMembership() {
      UserRealmRole membership = UserRealmRole.builder()
          .userId("alice@example.com")
          .realmRefName("pooled")
          .roles(List.of("user"))
          .authorizedTenantIds(List.of("org-a", "org-b"))
          .externalTenantIds(List.of("org-b"))
          .status(UserRealmRole.STATUS_ACTIVE)
          .build();

      assertFalse(membership.externalInTenant("org-a"));
      assertTrue(membership.externalInTenant("org-b"));
      assertDoesNotThrow(membership::validate);
   }

   @Test
   void blankOrNullExternalTenantEntryIsRefused() {
      UserRealmRole blank = UserRealmRole.builder()
          .userId("alice@example.com")
          .realmRefName("pooled")
          .externalTenantIds(List.of(" "))
          .build();
      UserRealmRole withNull = UserRealmRole.builder()
          .userId("alice@example.com")
          .realmRefName("pooled")
          .externalTenantIds(new ArrayList<>(Arrays.asList("org-a", null)))
          .build();

      assertThrows(IllegalArgumentException.class, blank::validate);
      assertThrows(IllegalArgumentException.class, withNull::validate);
   }

   @Test
   void noRoleGrantingRecordMayGrantExternal() {
      for (String variant : List.of("external", "EXTERNAL", " External ")) {
         UserRealmRole realmRole = UserRealmRole.builder()
             .userId("alice@example.com")
             .realmRefName("pooled")
             .roles(List.of("user", variant))
             .build();
         assertThrows(IllegalArgumentException.class, realmRole::validate, variant);

         CredentialUserIdPassword credential = new CredentialUserIdPassword();
         credential.setUserId("alice@example.com");
         credential.setSubject("sub-alice");
         credential.setDomainContext(DomainContext.builder()
             .tenantId("acme")
             .orgRefName("acme")
             .defaultRealm("pooled")
             .accountId("0000000001")
             .build());
         credential.setLastUpdate(new Date());
         credential.setRoles(new String[]{"user", variant});
         assertThrows(IllegalArgumentException.class, credential::validate, variant);

         UserGroup group = new UserGroup();
         group.setRefName("auditors");
         group.setRoles(List.of(variant));
         assertThrows(IllegalArgumentException.class, group::validate, variant);

         AccessInvite invite = AccessInvite.builder()
             .invitedByUserId("admin@acme.com")
             .tokenHash("hash")
             .expiresAt(new Date())
             .grantedRoles(List.of(variant))
             .build();
         assertThrows(IllegalArgumentException.class, invite::validate, variant);
      }

      assertEquals("external", UserRealmRole.ROLE_EXTERNAL);
      assertTrue(ApplicationDeclarationValidator.RESERVED_ROLES.contains(UserRealmRole.ROLE_EXTERNAL));
      assertTrue(ApplicationBindingValidator.RESERVED_ROLES.contains(UserRealmRole.ROLE_EXTERNAL));
   }
}
