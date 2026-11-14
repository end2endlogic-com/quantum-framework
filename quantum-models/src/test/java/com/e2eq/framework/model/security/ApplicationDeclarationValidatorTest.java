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
      Declaration legacy = new Declaration(app, List.of(), List.of(tagged(domain("LEGACY", "THING", "LIST"), "legacy-app")),
         List.of(legacyPolicy()));
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
         List.of(planner().domains().get(0), tagged(domain("OTHER", "THING", "LIST"), "acme-planner")), planner().policies());
      assertCodes(d, Code.DOMAIN_AREA_UNDECLARED);
   }

   @Test
   void legacyPackIsCleanEvenUnderRefuse() {
      Application app = new Application();
      app.setRefName("legacy-app");
      Declaration legacy = new Declaration(app, List.of(), List.of(tagged(domain("LEGACY", "THING", "LIST"), "legacy-app")),
         List.of(legacyPolicy()));
      List<Diagnostic> out = ApplicationDeclarationValidator.validate(legacy, PLATFORM, VocabularyEnforcement.REFUSE);
      assertTrue(out.isEmpty(), out.toString());
   }

   @Test
   void declarationErrorsStayErrorsInWarnMode() {
      Declaration d = planner();
      d.application().setOwnerRoles(List.of("boss"));
      List<Diagnostic> out = ApplicationDeclarationValidator.validate(d, PLATFORM, VocabularyEnforcement.WARN);
      assertTrue(out.stream().anyMatch(x -> x.code() == Code.OWNER_ROLE_UNDECLARED && x.severity() == Severity.ERROR), out.toString());
      assertTrue(ApplicationDeclarationValidator.refuses(out));
   }

   @Test
   void caseOfEnumeratedValuesAndApplicationIdsDoesNotMatter() {
      Declaration d = planner();
      d.application().getRoles().get(0).setGrantableBy(List.of("Tenant-Admin"));
      d.application().setTenancy(ApplicationTenancy.builder().placement("pooled").build());
      d.application().getUses().get(0).setApplication("Quantum-System");
      List<Diagnostic> out = ApplicationDeclarationValidator.validate(d, PLATFORM, VocabularyEnforcement.REFUSE);
      assertTrue(out.isEmpty(), out.toString());
   }

   @Test
   void anAreaOrDomainTaggedForAnotherApplicationIsRefused() {
      Declaration d = planner();
      FunctionalArea billing = new FunctionalArea();
      billing.setRefName("BILLING");
      billing.setApplicationId("acme-billing");
      FunctionalDomain invoice = domain("PLANNER", "INVOICE", "LIST");
      invoice.setApplicationId("acme-billing");
      Declaration tagged = new Declaration(d.application(), List.of(d.areas().get(0), billing),
         List.of(d.domains().get(0), invoice), d.policies());
      assertExactly(tagged, Code.VOCABULARY_APPLICATION_MISMATCH);
   }

   @Test
   void aPolicyForAnotherApplicationIsRefusedAndAWildcardOneIsVocabulary() {
      Declaration d = planner();
      Policy other = policy("planner-user", rule("planner-user", "PLANNER", "PLAN", "LIST"));
      other.setRefName("OTHER_POLICY");
      other.setApplicationId("acme-billing");
      Declaration withOther = new Declaration(d.application(), d.areas(), d.domains(), List.of(d.policies().get(0), d.policies().get(1), other));
      assertExactly(withOther, Code.POLICY_APPLICATION_MISMATCH);

      Policy everywhere = policy("planner-user", rule("planner-user", "PLANNER", "PLAN", "LIST"));
      everywhere.setRefName("EVERYWHERE_POLICY");
      everywhere.setApplicationId("*");
      Declaration withWildcard = new Declaration(d.application(), d.areas(), d.domains(), List.of(d.policies().get(0), d.policies().get(1), everywhere));
      List<Diagnostic> warn = ApplicationDeclarationValidator.validate(withWildcard, PLATFORM, VocabularyEnforcement.WARN);
      assertEquals(List.of(Code.POLICY_APPLICATION_MISMATCH), warn.stream().map(Diagnostic::code).toList(), warn.toString());
      assertFalse(ApplicationDeclarationValidator.refuses(warn));
   }

   @Test
   void aUserPolicyDoesNotMakeARoleUsed() {
      Declaration d = planner();
      d.application().getRoles().add(role("planner-auditor"));
      Policy user = policy("planner-auditor", rule("planner-auditor", "PLANNER", "PLAN", "VIEW"));
      user.setRefName("AUDITOR_USER_POLICY");
      user.setPrincipalType(Policy.PrincipalType.USER);
      Declaration withUser = new Declaration(d.application(), d.areas(), d.domains(), List.of(d.policies().get(0), d.policies().get(1), user));
      assertExactly(withUser, Code.ROLE_UNUSED);
   }

   @Test
   void usesWithNoDomainsIsReported() {
      Declaration d = planner();
      d.application().getUses().add(VocabularyReference.builder().application("quantum-system").area("SECURITY")
         .domains(List.of()).build());
      assertExactly(d, Code.USES_UNKNOWN);
   }

   @Test
   void aRuleWithNoDomainIsNotAWildcard() throws Exception {
      Declaration d = planner();
      Rule rule = rule("planner-user", "PLANNER", "PLAN", "LIST");
      // The setter refuses null; a stored document can still lack the field.
      java.lang.reflect.Field field = SecurityURIHeader.class.getDeclaredField("functionalDomain");
      field.setAccessible(true);
      field.set(rule.getSecurityURI().getHeader(), null);
      d.policies().get(0).getRules().add(rule);
      assertExactly(d, Code.POLICY_TRIPLE_UNDECLARED);
   }

   @Test
   void aPolicyWithNoApplicationIdReachesEveryApplication() {
      Declaration d = planner();
      d.policies().get(0).setApplicationId(null);
      List<Diagnostic> warn = ApplicationDeclarationValidator.validate(d, PLATFORM, VocabularyEnforcement.WARN);
      assertEquals(List.of(Code.POLICY_APPLICATION_MISMATCH), warn.stream().map(Diagnostic::code).toList(), warn.toString());
      assertFalse(ApplicationDeclarationValidator.refuses(warn));
      assertExactly(d, Code.POLICY_APPLICATION_MISMATCH);
   }

   @Test
   void malformedVocabularyRowsAreReported() {
      Declaration d = planner();
      FunctionalArea nameless = new FunctionalArea();
      nameless.setApplicationId("acme-planner");
      FunctionalDomain arealess = tagged(domain(null, "ORPHAN", "LIST"), "acme-planner");
      Rule headerless = new Rule();
      headerless.setName("headerless");
      d.policies().get(0).getRules().add(headerless);
      Declaration bad = new Declaration(d.application(), List.of(d.areas().get(0), nameless),
         List.of(d.domains().get(0), arealess), d.policies());
      List<Diagnostic> out = ApplicationDeclarationValidator.validate(bad, PLATFORM, VocabularyEnforcement.REFUSE);
      assertEquals(3, out.stream().filter(x -> x.code() == Code.VOCABULARY_ROW_INVALID).count(), out.toString());
      assertExactly(bad, Code.VOCABULARY_ROW_INVALID);
   }

   @Test
   void untaggedAreasAndDomainsRecordNoOwner() {
      Declaration d = planner();
      d.areas().get(0).setApplicationId(null);
      d.domains().get(0).setApplicationId("*");
      assertExactly(d, Code.VOCABULARY_APPLICATION_MISSING);
   }

   @Test
   void aPolicyOnAnOwnedAreaMayGrantOnlyDeclaredRoles() {
      Declaration d = planner();
      d.policies().get(0).getRules().add(rule("planer-user", "PLANNER", "PLAN", "LIST"));
      assertExactly(d, Code.POLICY_ROLE_UNDECLARED);

      Declaration reserved = planner();
      reserved.policies().get(0).getRules().add(rule("admin", "PLANNER", "PLAN", "LIST"));
      List<Diagnostic> out = ApplicationDeclarationValidator.validate(reserved, PLATFORM, VocabularyEnforcement.REFUSE);
      assertTrue(out.isEmpty(), out.toString());
   }

   @Test
   void anAreaHeldForEveryApplicationIsNotClaimable() {
      VocabularyRegistry shared = new VocabularyRegistry() {
         @Override public Optional<String> areaOwner(String area) {
            return "planner".equals(area) ? Optional.of("*") : PLATFORM.areaOwner(area);
         }
         @Override public Optional<Set<String>> actions(String application, String area, String domain) {
            return PLATFORM.actions(application, area, domain);
         }
      };
      List<Diagnostic> out = ApplicationDeclarationValidator.validate(planner(), shared, VocabularyEnforcement.REFUSE);
      assertEquals(List.of(Code.AREA_OWNED_ELSEWHERE), out.stream().map(Diagnostic::code).toList(), out.toString());
   }

   // ---- fixtures

   private static FunctionalDomain tagged(FunctionalDomain domain, String applicationId) {
      domain.setApplicationId(applicationId);
      return domain;
   }

   /** The pack is refused, with exactly these codes and nothing else. */
   private static void assertExactly(Declaration d, Code... expected) {
      List<Diagnostic> out = ApplicationDeclarationValidator.validate(d, PLATFORM, VocabularyEnforcement.REFUSE);
      assertEquals(Set.of(expected), Set.copyOf(out.stream().map(Diagnostic::code).toList()), out.toString());
      assertTrue(ApplicationDeclarationValidator.refuses(out));
   }

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
      return new Declaration(app, List.of(area), List.of(tagged(domain("PLANNER", "PLAN", "LIST", "VIEW", "PUBLISH"), "acme-planner")),
         List.of(user, admin));
   }

   private static Policy legacyPolicy() {
      Policy legacy = policy("legacy-user", rule("user", "LEGACY", "THING", "LIST"));
      legacy.setApplicationId("legacy-app");
      return legacy;
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
