package com.e2eq.framework.model.security;

import com.e2eq.framework.model.security.ApplicationDeclarationValidator.Code;
import com.e2eq.framework.model.security.ApplicationDeclarationValidator.Declaration;
import com.e2eq.framework.model.security.ApplicationDeclarationValidator.Diagnostic;
import com.e2eq.framework.model.security.ApplicationDeclarationValidator.Severity;
import com.e2eq.framework.model.security.ApplicationDeclarationValidator.VocabularyEnforcement;
import com.e2eq.framework.model.security.ApplicationDeclarationValidator.VocabularyRegistry;
import com.e2eq.framework.model.securityrules.SecurityURI;
import com.e2eq.framework.model.securityrules.SecurityURIHeader;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationDeclarationValidatorTest {

   /** The platform owns SYSTEM and SECURITY. */
   private static final VocabularyRegistry PLATFORM = new VocabularyRegistry() {
      final Map<String, Map<String, Set<String>>> vocab = Map.of(
         "system", Map.of("menuitem", Set.of("list", "view")),
         "security", Map.of("userrealmrole", Set.of("list", "view", "update")));

      @Override
      public Optional<String> areaOwner(String area) {
         return vocab.containsKey(area) ? Optional.of("quantum-system") : Optional.empty();
      }

      @Override
      public Optional<Set<String>> actions(String application, String area, String domain) {
         if (!"quantum-system".equals(application)) return Optional.empty();
         return Optional.ofNullable(vocab.getOrDefault(area, Map.of()).get(domain));
      }
   };

   @Test
   void wellFormedDeclarationPasses() {
      List<Diagnostic> out = ApplicationDeclarationValidator.validate(planner(), PLATFORM, VocabularyEnforcement.REFUSE);
      assertTrue(out.isEmpty(), out.toString());
   }

   @Test
   void legacyPackWithoutRolesHasNoDeclarationErrors() {
      Application app = new Application();
      app.setRefName("legacy-app");
      Declaration legacy = new Declaration(app, List.of(), List.of(domain("LEGACY", "THING", "LIST")),
         List.of(policy("legacy-user", rule("user", "LEGACY", "THING", "LIST"))));
      List<Diagnostic> out = ApplicationDeclarationValidator.validate(legacy, PLATFORM, VocabularyEnforcement.WARN);
      assertFalse(ApplicationDeclarationValidator.refuses(out), out.toString());
   }

   @Test
   void reservedDuplicateAndPrefixedRolesAreRefused() {
      Declaration d = planner();
      d.application().getRoles().add(role("admin"));
      d.application().getRoles().add(role("planner-user"));
      d.application().getRoles().add(role("acme-planner:admin"));
      assertCodes(d, Code.ROLE_RESERVED, Code.ROLE_DUPLICATE, Code.ROLE_NAME_INVALID);
   }

   @Test
   void ownerAndMemberRolesMustBeDeclared() {
      Declaration d = planner();
      d.application().setOwnerRoles(List.of("planner-admin", "boss"));
      d.application().setMemberRoles(List.of("guest"));
      assertCodes(d, Code.OWNER_ROLE_UNDECLARED, Code.MEMBER_ROLE_UNDECLARED);
   }

   @Test
   void aRoleNoPolicyNamesIsRefused() {
      Declaration d = planner();
      d.application().getRoles().add(role("planner-auditor"));
      assertCodes(d, Code.ROLE_UNUSED);
   }

   @Test
   void unknownGrantableByIsRefused() {
      Declaration d = planner();
      d.application().getRoles().get(0).setGrantableBy(List.of("anyone"));
      assertCodes(d, Code.ROLE_GRANTABLE_BY_INVALID);
   }

   @Test
   void tenancyIsChecked() {
      Declaration d = planner();
      d.application().setTenancy(ApplicationTenancy.builder().placement("REALMS").build());
      assertCodes(d, Code.TENANCY_REALMS_INVALID);
      d.application().setTenancy(ApplicationTenancy.builder().placement("POOLED").realms(List.of("x")).build());
      assertCodes(d, Code.TENANCY_REALMS_INVALID);
      d.application().setTenancy(ApplicationTenancy.builder().placement("SOMETIMES").build());
      assertCodes(d, Code.TENANCY_PLACEMENT_INVALID);
   }

   @Test
   void policyNamingAnUndeclaredTripleWarnsOrRefusesByMode() {
      Declaration d = planner();
      d.policies().get(0).getRules().add(rule("planner-user", "PLANNER", "PLAN", "ARCHIVE"));
      d.policies().get(0).getRules().add(rule("planner-user", "BILLING", "INVOICE", "LIST"));
      List<Diagnostic> warn = ApplicationDeclarationValidator.validate(d, PLATFORM, VocabularyEnforcement.WARN);
      assertEquals(2, warn.stream().filter(x -> x.code() == Code.POLICY_TRIPLE_UNDECLARED && x.severity() == Severity.WARNING).count(), warn.toString());
      assertFalse(ApplicationDeclarationValidator.refuses(warn));
      assertTrue(ApplicationDeclarationValidator.refuses(
         ApplicationDeclarationValidator.validate(d, PLATFORM, VocabularyEnforcement.REFUSE)));
   }

   @Test
   void areaWildcardReachesOutsideTheApplication() {
      Declaration d = planner();
      d.policies().get(0).getRules().add(rule("planner-admin", "*", "*", "*"));
      assertCodes(d, Code.POLICY_WILDCARD_OUTSIDE_APPLICATION);
   }

   @Test
   void borrowedVocabularyOnlyForOwnRoles() {
      Declaration d = planner();
      d.policies().get(0).getRules().add(rule("user", "SYSTEM", "MENUITEM", "LIST"));
      assertCodes(d, Code.POLICY_BORROWED_FOREIGN_ROLE);
   }

   @Test
   void borrowedActionsCannotBeExtended() {
      Declaration d = planner();
      d.policies().get(0).getRules().add(rule("planner-user", "SYSTEM", "MENUITEM", "DELETE"));
      assertCodes(d, Code.POLICY_TRIPLE_UNDECLARED);
   }

   @Test
   void declaringAnotherApplicationsAreaIsRefused() {
      Declaration d = new Declaration(planner().application(), List.of(),
         List.of(domain("PLANNER", "PLAN", "LIST"), domain("SYSTEM", "MENUITEM", "LIST", "PURGE")), planner().policies());
      assertCodes(d, Code.AREA_OWNED_ELSEWHERE);
   }

   @Test
   void usesMustNameRegisteredDomains() {
      Declaration d = planner();
      d.application().getUses().add(VocabularyReference.builder().application("quantum-system").area("SYSTEM")
         .domains(List.of("NOPE")).build());
      assertCodes(d, Code.USES_UNKNOWN);
   }

   @Test
   void domainOutsideDeclaredAreasIsReported() {
      Declaration d = new Declaration(planner().application(), planner().areas(),
         List.of(domain("PLANNER", "PLAN", "LIST", "VIEW", "PUBLISH"), domain("OTHER", "THING", "LIST")), planner().policies());
      assertCodes(d, Code.DOMAIN_AREA_UNDECLARED);
   }

   // ---- fixtures

   private static void assertCodes(Declaration d, Code... expected) {
      List<Diagnostic> out = ApplicationDeclarationValidator.validate(d, PLATFORM, VocabularyEnforcement.REFUSE);
      Set<Code> codes = Set.copyOf(out.stream().map(Diagnostic::code).toList());
      for (Code code : expected) {
         assertTrue(codes.contains(code), "expected " + code + " in " + out);
      }
      assertTrue(ApplicationDeclarationValidator.refuses(out));
   }

   private static Declaration planner() {
      Application app = new Application();
      app.setRefName("acme-planner");
      app.setRoles(new ArrayList<>(List.of(
         ApplicationRole.builder().name("planner-admin").grantableBy(List.of("tenant-admin")).build(),
         ApplicationRole.builder().name("planner-user").grantableBy(List.of("tenant-admin")).build())));
      app.setOwnerRoles(List.of("planner-admin", "planner-user"));
      app.setMemberRoles(List.of("planner-user"));
      app.setTenancy(ApplicationTenancy.builder().placement("POOLED").build());
      app.setUses(new ArrayList<>(List.of(VocabularyReference.builder().application("quantum-system").area("SYSTEM")
         .domains(List.of("MENUITEM")).build())));
      FunctionalArea area = new FunctionalArea();
      area.setRefName("PLANNER");
      area.setApplicationId("acme-planner");
      Policy user = policy("planner-user",
         rule("planner-user", "PLANNER", "PLAN", "LIST"),
         rule("planner-user", "PLANNER", "PLAN", "VIEW"),
         rule("planner-user", "SYSTEM", "MENUITEM", "LIST"));
      Policy admin = policy("planner-admin", rule("planner-admin", "PLANNER", "*", "*"));
      return new Declaration(app, List.of(area), List.of(domain("PLANNER", "PLAN", "LIST", "VIEW", "PUBLISH")),
         List.of(user, admin));
   }

   private static ApplicationRole role(String name) {
      return ApplicationRole.builder().name(name).grantableBy(List.of("tenant-admin")).build();
   }

   private static FunctionalDomain domain(String area, String name, String... actions) {
      FunctionalDomain domain = new FunctionalDomain();
      domain.setArea(area);
      domain.setRefName(name);
      List<FunctionalAction> list = new ArrayList<>();
      for (String a : actions) {
         FunctionalAction action = new FunctionalAction();
         action.setRefName(a);
         list.add(action);
      }
      domain.setFunctionalActions(list);
      return domain;
   }

   private static Policy policy(String principal, Rule... rules) {
      Policy policy = new Policy();
      policy.setRefName(principal.toUpperCase() + "_POLICY");
      policy.setPrincipalId(principal);
      policy.setApplicationId("acme-planner");
      policy.setRules(new ArrayList<>(List.of(rules)));
      return policy;
   }

   private static Rule rule(String identity, String area, String domain, String action) {
      Rule rule = new Rule();
      rule.setName(identity + "-" + area + "-" + domain + "-" + action);
      SecurityURI uri = new SecurityURI();
      uri.setHeader(new SecurityURIHeader(identity, area, domain, action));
      rule.setSecurityURI(uri);
      return rule;
   }
}
