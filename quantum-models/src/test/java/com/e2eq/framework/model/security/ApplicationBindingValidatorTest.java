package com.e2eq.framework.model.security;

import com.e2eq.framework.model.security.ApplicationAccessException.Code;
import com.e2eq.framework.model.security.ApplicationBindingValidator.Authority;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApplicationBindingValidatorTest {

   private static final Predicate<String> ALL_INSTALLED = tenant -> true;

   @Test
   void aWellFormedBindingPasses() {
      assertDoesNotThrow(() -> ApplicationBindingValidator.validate(
         binding(List.of("t1", "t2"), "t2", "planner-user"), planner(), ALL_INSTALLED));
      assertDoesNotThrow(() -> ApplicationBindingValidator.validate(
         binding(List.of("t1"), null), planner(), ALL_INSTALLED), "no roles: admits the application only");
   }

   @Test
   void anUndeclaredRoleIsRefused() {
      refused(Code.ROLE_NOT_DECLARED, binding(List.of("t1"), null, "planner-superuser"), planner(), ALL_INSTALLED);
      Application legacy = new Application();
      legacy.setRefName("planner");
      refused(Code.ROLE_NOT_DECLARED, binding(List.of("t1"), null, "planner-user"), legacy, ALL_INSTALLED);
   }

   @Test
   void severalTenantsNeedADefaultAmongThem() {
      refused(Code.TENANT_DEFAULT_REQUIRED, binding(List.of("t1", "t2"), null, "planner-user"), planner(), ALL_INSTALLED);
      refused(Code.TENANT_DEFAULT_REQUIRED, binding(List.of("t1", "t2"), "t3", "planner-user"), planner(), ALL_INSTALLED);
      refused(Code.TENANT_DEFAULT_REQUIRED, binding(List.of("t1"), "t3", "planner-user"), planner(), ALL_INSTALLED);
   }

   @Test
   void aBindingNamesDistinctTenants() {
      refused(Code.TENANTS_REQUIRED, binding(List.of(), null, "planner-user"), planner(), ALL_INSTALLED);
      refused(Code.TENANTS_REQUIRED, binding(List.of("t1", "t1"), "t1", "planner-user"), planner(), ALL_INSTALLED);
      refused(Code.TENANTS_REQUIRED, binding(new ArrayList<>(java.util.Arrays.asList("t1", " ")), "t1"), planner(), ALL_INSTALLED);
   }

   @Test
   void everyTenantNeedsAnInstall() {
      refused(Code.APPLICATION_NOT_INSTALLED, binding(List.of("t1", "t2"), "t1", "planner-user"), planner(),
         Set.of("t1")::contains);
      refused(Code.APPLICATION_NOT_INSTALLED, binding(List.of("t1"), null), planner(), tenant -> false);
   }

   @Test
   void aBindingNamesExactlyOneSubjectApplicationAndRealm() {
      UserAppRole both = binding(List.of("t1"), null);
      both.setGroupRefName("planners");
      refused(Code.BINDING_SUBJECT_INVALID, both, planner(), ALL_INSTALLED);

      UserAppRole neither = binding(List.of("t1"), null);
      neither.setUserId(null);
      refused(Code.BINDING_SUBJECT_INVALID, neither, planner(), ALL_INSTALLED);

      UserAppRole noRealm = binding(List.of("t1"), null);
      noRealm.setRealmRefName(" ");
      refused(Code.BINDING_INVALID, noRealm, planner(), ALL_INSTALLED);

      UserAppRole badStatus = binding(List.of("t1"), null);
      badStatus.setStatus("revoked");
      refused(Code.BINDING_INVALID, badStatus, planner(), ALL_INSTALLED);

      UserAppRole otherApp = binding(List.of("t1"), null);
      otherApp.setApplicationId("reports");
      refused(Code.APPLICATION_UNKNOWN, otherApp, planner(), ALL_INSTALLED);
      refused(Code.APPLICATION_UNKNOWN, binding(List.of("t1"), null), null, ALL_INSTALLED);
   }

   @Test
   void grantableByDecidesWhoMayGrantEachRole() {
      Application app = planner();
      List<String> tenantRole = List.of("planner-user");
      List<String> realmRole = List.of("planner-admin");
      List<String> platformRole = List.of("planner-operator");

      assertDoesNotThrow(() -> ApplicationBindingValidator.requireGrantable(app, tenantRole, Authority.TENANT_ADMIN));
      assertGrantRefused(app, realmRole, Authority.TENANT_ADMIN);
      assertGrantRefused(app, platformRole, Authority.TENANT_ADMIN);

      assertDoesNotThrow(() -> ApplicationBindingValidator.requireGrantable(app, tenantRole, Authority.REALM_ADMIN));
      assertDoesNotThrow(() -> ApplicationBindingValidator.requireGrantable(app, realmRole, Authority.REALM_ADMIN));
      assertGrantRefused(app, platformRole, Authority.REALM_ADMIN);

      assertDoesNotThrow(() -> ApplicationBindingValidator.requireGrantable(app, platformRole, Authority.PLATFORM));
      assertGrantRefused(app, tenantRole, Authority.NONE);
      assertGrantRefused(app, List.of("system"), Authority.PLATFORM);
      assertEquals(Code.ROLE_NOT_DECLARED, assertThrows(ApplicationAccessException.class,
         () -> ApplicationBindingValidator.requireGrantable(app, List.of("undeclared"), Authority.PLATFORM)).code());
   }

   @Test
   void thePlatformsReservedRolesAreNeverBoundWhateverGrantableBySays() {
      // An application row that declares the platform's roles anyway, grantable by anyone.
      Application app = planner();
      for (String reserved : ApplicationDeclarationValidator.RESERVED_ROLES) {
         app.getRoles().add(role(reserved, ApplicationRole.GRANTABLE_BY_TENANT_ADMIN));
      }
      for (String reserved : List.of("system", "admin", "user", "Admin", " user ")) {
         refused(Code.ROLE_NOT_GRANTABLE, binding(List.of("t1"), null, reserved), app, ALL_INSTALLED);
         for (Authority authority : Authority.values()) {
            assertGrantRefused(app, List.of(reserved), authority);
         }
      }
      // And for an application that does not declare them.
      refused(Code.ROLE_NOT_GRANTABLE, binding(List.of("t1"), null, "admin"), planner(), ALL_INSTALLED);
   }

   @Test
   void privilegedRoleNamesAreNeverBoundWhateverGrantableBySays() {
      // Binding roles join the global roles, so a name @RolesAllowed checks or the platform
      // recognizes would grant platform authority: refused without case, configured ones too.
      List<String> configured = List.of("membership-admin");
      Application app = planner();
      List<String> privileged = List.of("platformAdmin", "PLATFORMADMIN", "tenantAdmin", "TenantAdmin", "ANONYMOUS",
         "anonymous", "membership-admin", "Membership-Admin");
      privileged.forEach(name -> app.getRoles().add(role(name, ApplicationRole.GRANTABLE_BY_TENANT_ADMIN)));
      for (String name : privileged) {
         ApplicationAccessException saved = assertThrows(ApplicationAccessException.class,
            () -> ApplicationBindingValidator.validate(binding(List.of("t1"), null, name), app, ALL_INSTALLED, configured));
         assertEquals(Code.ROLE_NOT_GRANTABLE, saved.code(), name);
         for (Authority authority : Authority.values()) {
            ApplicationAccessException granted = assertThrows(ApplicationAccessException.class,
               () -> ApplicationBindingValidator.requireGrantable(app, List.of(name), authority, configured));
            assertEquals(Code.ROLE_NOT_GRANTABLE, granted.code(), name + " by " + authority);
         }
      }
      // An application's own role that merely resembles one is still grantable.
      app.getRoles().add(role("membership-admin-viewer", ApplicationRole.GRANTABLE_BY_TENANT_ADMIN));
      ApplicationBindingValidator.requireGrantable(app, List.of("membership-admin-viewer"), Authority.TENANT_ADMIN, configured);
   }

   @Test
   void aSuspendedBindingIsSavedWithoutAnInstall() {
      UserAppRole suspended = binding(List.of("t1"), null, "planner-user");
      suspended.setStatus(UserAppRole.STATUS_SUSPENDED);
      ApplicationBindingValidator.validate(suspended, planner(), tenant -> false);
      refused(Code.APPLICATION_NOT_INSTALLED, binding(List.of("t1"), null, "planner-user"), planner(), tenant -> false);
   }

   // --- harness ---

   private static void assertGrantRefused(Application app, List<String> roles, Authority authority) {
      ApplicationAccessException refusal = assertThrows(ApplicationAccessException.class,
         () -> ApplicationBindingValidator.requireGrantable(app, roles, authority));
      assertEquals(Code.ROLE_NOT_GRANTABLE, refusal.code(), refusal.getMessage());
   }

   private static void refused(Code expected, UserAppRole binding, Application application, Predicate<String> installed) {
      ApplicationAccessException refusal = assertThrows(ApplicationAccessException.class,
         () -> ApplicationBindingValidator.validate(binding, application, installed));
      assertEquals(expected, refusal.code(), refusal.getMessage());
   }

   static Application planner() {
      Application app = new Application();
      app.setRefName("planner");
      app.setRoles(new ArrayList<>(List.of(
         role("planner-user", ApplicationRole.GRANTABLE_BY_TENANT_ADMIN),
         role("planner-admin", ApplicationRole.GRANTABLE_BY_REALM_ADMIN),
         role("planner-operator", ApplicationRole.GRANTABLE_BY_PLATFORM))));
      return app;
   }

   private static ApplicationRole role(String name, String grantableBy) {
      return ApplicationRole.builder().name(name).grantableBy(List.of(grantableBy)).build();
   }

   private static UserAppRole binding(List<String> tenants, String defaultTenant, String... roles) {
      UserAppRole binding = new UserAppRole();
      binding.setUserId("alice@acme");
      binding.setApplicationId("planner");
      binding.setRealmRefName("pooled");
      binding.setTenantIds(new ArrayList<>(tenants));
      binding.setDefaultTenantId(defaultTenant);
      binding.setRoles(new ArrayList<>(List.of(roles)));
      return binding;
   }
}
