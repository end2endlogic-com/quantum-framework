package com.e2eq.framework.model.security;

import com.e2eq.framework.model.securityrules.SecurityURIHeader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Checks an application's declaration (its roles, owner and member roles, tenancy and policy
 * vocabulary) the way identity-contribution admission must. Pure: the caller supplies what the
 * pack carries and a {@link VocabularyRegistry} over what other applications already own.
 *
 * <p>Declaration checks (roles, owner/member roles, tenancy) are always errors, and run only for
 * what the application declares, so a pack that declares no roles keeps the legacy path.
 * Vocabulary checks (policy rules against the vocabulary, borrowed domains) are errors or
 * warnings by {@link VocabularyEnforcement}, so existing packs can be cleaned up before they are
 * refused.
 */
public final class ApplicationDeclarationValidator {

   /** Roles the platform owns. An application never declares them as its own roles. */
   public static final Set<String> RESERVED_ROLES = Set.of("system", "admin", "user");

   private static final String WILDCARD = "*";

   public enum VocabularyEnforcement { WARN, REFUSE }

   public enum Severity { ERROR, WARNING }

   public enum Code {
      APPLICATION_ID_MISSING,
      ROLE_NAME_INVALID,
      ROLE_DUPLICATE,
      ROLE_RESERVED,
      ROLE_GRANTABLE_BY_INVALID,
      ROLE_UNUSED,
      OWNER_ROLE_UNDECLARED,
      MEMBER_ROLE_UNDECLARED,
      TENANCY_PLACEMENT_INVALID,
      TENANCY_REALMS_INVALID,
      AREA_OWNED_ELSEWHERE,
      DOMAIN_AREA_UNDECLARED,
      USES_OWN_AREA,
      USES_UNKNOWN,
      POLICY_TRIPLE_UNDECLARED,
      POLICY_WILDCARD_OUTSIDE_APPLICATION,
      POLICY_BORROWED_FOREIGN_ROLE
   }

   public record Diagnostic(Code code, Severity severity, String message) {
      public Diagnostic {
         Objects.requireNonNull(code, "code");
         Objects.requireNonNull(severity, "severity");
      }
   }

   /**
    * What other applications already own, as the issuer holds it. Areas, domains and actions are
    * passed and compared in lower case, as policy headers hold them.
    */
   public interface VocabularyRegistry {
      /** The application that owns {@code area}, if any other pack declared it. */
      Optional<String> areaOwner(String area);

      /**
       * The actions of {@code area}/{@code domain} as {@code application} declares them, or empty
       * when that application has no such domain.
       */
      Optional<Set<String>> actions(String application, String area, String domain);
   }

   /** What a pack carries: its application row, owned areas and domains, and its policies. */
   public record Declaration(Application application,
                             List<FunctionalArea> areas,
                             List<FunctionalDomain> domains,
                             List<Policy> policies) {
      public Declaration {
         areas = areas == null ? List.of() : List.copyOf(areas);
         domains = domains == null ? List.of() : List.copyOf(domains);
         policies = policies == null ? List.of() : List.copyOf(policies);
      }
   }

   private ApplicationDeclarationValidator() {
   }

   public static List<Diagnostic> validate(Declaration declaration,
                                           VocabularyRegistry registry,
                                           VocabularyEnforcement enforcement) {
      Objects.requireNonNull(declaration, "declaration");
      Objects.requireNonNull(registry, "registry");
      Severity vocabulary = enforcement == VocabularyEnforcement.REFUSE ? Severity.ERROR : Severity.WARNING;
      List<Diagnostic> out = new ArrayList<>();
      Application application = declaration.application();
      String applicationId = application == null ? null : trim(application.getRefName());
      if (applicationId == null) {
         out.add(error(Code.APPLICATION_ID_MISSING, "The application row names no refName."));
         return out;
      }

      Set<String> declaredRoles = checkRoles(application, out);
      checkTenancy(application.getTenancy(), out);

      // Owned vocabulary: declared areas, plus the areas the pack's domains name (packs from
      // before FunctionalArea records declare areas only through their domains).
      Map<String, Map<String, Set<String>>> owned = ownedVocabulary(declaration, applicationId, registry, vocabulary, out);
      Map<String, Map<String, Set<String>>> borrowed = borrowedVocabulary(application, applicationId, owned, registry, vocabulary, out);

      Set<String> namedRoles = new LinkedHashSet<>();
      for (Policy policy : declaration.policies()) {
         checkPolicy(policy, applicationId, owned, borrowed, declaredRoles, vocabulary, out, namedRoles);
      }
      for (String role : declaredRoles) {
         if (!namedRoles.contains(role)) { // both keyed lowercase
            out.add(error(Code.ROLE_UNUSED, "Role '" + role + "' is declared but no policy of " + applicationId
               + " names it, so it would grant nothing."));
         }
      }
      return out;
   }

   /** True when any diagnostic must refuse the pack. */
   public static boolean refuses(List<Diagnostic> diagnostics) {
      return diagnostics.stream().anyMatch(d -> d.severity() == Severity.ERROR);
   }

   private static Set<String> checkRoles(Application application, List<Diagnostic> out) {
      Set<String> declared = new LinkedHashSet<>();
      List<ApplicationRole> roles = application.getRoles() == null ? List.of() : application.getRoles();
      for (ApplicationRole role : roles) {
         String name = role == null ? null : trim(role.getName());
         if (name == null || name.contains(WILDCARD) || name.contains(":")) {
            out.add(error(Code.ROLE_NAME_INVALID, "A declared role needs a plain name (no '*' or ':' prefix): "
               + (role == null ? "null" : role.getName())));
            continue;
         }
         if (RESERVED_ROLES.contains(name.toLowerCase(Locale.ROOT))) {
            out.add(error(Code.ROLE_RESERVED, "Role '" + name + "' belongs to the platform; declare the application's own role."));
            continue;
         }
         if (!declared.add(key(name))) {
            out.add(error(Code.ROLE_DUPLICATE, "Role '" + name + "' is declared twice."));
            continue;
         }
         List<String> grantableBy = role.getGrantableBy() == null ? List.of() : role.getGrantableBy();
         for (String who : grantableBy) {
            if (who == null || !ApplicationRole.GRANTABLE_BY_VALUES.contains(who.trim())) {
               out.add(error(Code.ROLE_GRANTABLE_BY_INVALID, "Role '" + name + "' grantableBy '" + who
                  + "' is not one of " + ApplicationRole.GRANTABLE_BY_VALUES + "."));
            }
         }
      }
      for (String owner : nonNull(application.getOwnerRoles())) {
         if (!declared.contains(key(owner))) {
            out.add(error(Code.OWNER_ROLE_UNDECLARED, "ownerRoles names '" + owner + "', which the application does not declare."));
         }
      }
      for (String member : nonNull(application.getMemberRoles())) {
         if (!declared.contains(key(member))) {
            out.add(error(Code.MEMBER_ROLE_UNDECLARED, "memberRoles names '" + member + "', which the application does not declare."));
         }
      }
      return declared;
   }

   private static void checkTenancy(ApplicationTenancy tenancy, List<Diagnostic> out) {
      if (tenancy == null) {
         return;
      }
      String placement = trim(tenancy.getPlacement());
      if (placement == null || !ApplicationTenancy.PLACEMENT_VALUES.contains(placement)) {
         out.add(error(Code.TENANCY_PLACEMENT_INVALID, "tenancy.placement '" + tenancy.getPlacement()
            + "' is not one of " + ApplicationTenancy.PLACEMENT_VALUES + "."));
         return;
      }
      List<String> realms = nonNull(tenancy.getRealms()).stream().map(ApplicationDeclarationValidator::trim)
         .filter(Objects::nonNull).toList();
      if (ApplicationTenancy.PLACEMENT_REALMS.equals(placement) && realms.isEmpty()) {
         out.add(error(Code.TENANCY_REALMS_INVALID, "tenancy.placement REALMS needs at least one realm."));
      }
      if (!ApplicationTenancy.PLACEMENT_REALMS.equals(placement) && !realms.isEmpty()) {
         out.add(error(Code.TENANCY_REALMS_INVALID, "tenancy.realms is only for placement REALMS, not " + placement + "."));
      }
      if (realms.stream().anyMatch(realm -> realm.contains(WILDCARD))) {
         out.add(error(Code.TENANCY_REALMS_INVALID, "tenancy.realms names concrete realms only, no '*'."));
      }
   }

   private static Map<String, Map<String, Set<String>>> ownedVocabulary(Declaration declaration, String applicationId,
                                                                    VocabularyRegistry registry, Severity vocabulary,
                                                                    List<Diagnostic> out) {
      Map<String, Map<String, Set<String>>> owned = new LinkedHashMap<>();
      Set<String> declaredAreas = new LinkedHashSet<>();
      for (FunctionalArea area : declaration.areas()) {
         String name = area == null ? null : key(area.getRefName());
         if (name != null) {
            declaredAreas.add(name);
            owned.computeIfAbsent(name, k -> new LinkedHashMap<>());
         }
      }
      for (FunctionalDomain domain : declaration.domains()) {
         String area = key(domain.getArea());
         String name = key(domain.getRefName());
         if (area == null || name == null) {
            continue;
         }
         if (!declaredAreas.isEmpty() && !declaredAreas.contains(area)) {
            out.add(new Diagnostic(Code.DOMAIN_AREA_UNDECLARED, vocabulary, "Domain " + area + "/" + name
               + " names an area the pack does not declare."));
         }
         Set<String> actions = owned.computeIfAbsent(area, k -> new LinkedHashMap<>())
            .computeIfAbsent(name, k -> new LinkedHashSet<>());
         for (FunctionalAction action : nonNull(domain.getFunctionalActions())) {
            String ref = action == null ? null : key(action.getRefName());
            if (ref != null) {
               actions.add(ref);
            }
         }
      }
      for (String area : owned.keySet()) {
         Optional<String> owner = registry.areaOwner(area).map(ApplicationDeclarationValidator::trim).filter(Objects::nonNull);
         if (owner.isPresent() && !owner.get().equalsIgnoreCase(applicationId) && !WILDCARD.equals(owner.get())) {
            out.add(new Diagnostic(Code.AREA_OWNED_ELSEWHERE, vocabulary, "Area " + area + " belongs to " + owner.get()
               + "; borrow its domains with 'uses' instead of declaring them."));
         }
      }
      return owned;
   }

   private static Map<String, Map<String, Set<String>>> borrowedVocabulary(Application application, String applicationId,
                                                                       Map<String, Map<String, Set<String>>> owned,
                                                                       VocabularyRegistry registry, Severity vocabulary,
                                                                       List<Diagnostic> out) {
      Map<String, Map<String, Set<String>>> borrowed = new LinkedHashMap<>();
      for (VocabularyReference reference : nonNull(application.getUses())) {
         String from = reference == null ? null : trim(reference.getApplication());
         String area = reference == null ? null : key(reference.getArea());
         if (from == null || area == null) {
            out.add(new Diagnostic(Code.USES_UNKNOWN, vocabulary, "A 'uses' entry must name an application and an area."));
            continue;
         }
         if (from.equalsIgnoreCase(applicationId) || owned.containsKey(area)) {
            out.add(new Diagnostic(Code.USES_OWN_AREA, vocabulary, "'uses' names " + from + "/" + area
               + ", which this application owns; owned areas need no reference."));
            continue;
         }
         for (String domain : nonNull(reference.getDomains())) {
            String name = key(domain);
            Optional<Set<String>> actions = name == null ? Optional.empty() : registry.actions(from, area, name);
            if (actions.isEmpty()) {
               out.add(new Diagnostic(Code.USES_UNKNOWN, vocabulary, "'uses' names " + from + ":" + area + "/" + domain
                  + ", which " + from + " does not declare."));
               continue;
            }
            Set<String> keyed = new LinkedHashSet<>();
            actions.get().forEach(a -> { String k = key(a); if (k != null) keyed.add(k); });
            borrowed.computeIfAbsent(area, k -> new LinkedHashMap<>()).put(name, keyed);
         }
      }
      return borrowed;
   }

   private static void checkPolicy(Policy policy, String applicationId,
                                   Map<String, Map<String, Set<String>>> owned,
                                   Map<String, Map<String, Set<String>>> borrowed,
                                   Set<String> declaredRoles, Severity vocabulary,
                                   List<Diagnostic> out, Set<String> namedRoles) {
      if (policy == null) {
         return;
      }
      String principal = key(policy.getPrincipalId());
      boolean rolePrincipal = policy.getPrincipalType() == null || policy.getPrincipalType() == Policy.PrincipalType.ROLE;
      if (rolePrincipal && principal != null) {
         namedRoles.add(principal);
      }
      for (Rule rule : nonNull(policy.getRules())) {
         SecurityURIHeader header = rule == null || rule.getSecurityURI() == null ? null : rule.getSecurityURI().getHeader();
         if (header == null) {
            continue;
         }
         String identity = key(header.getIdentity());
         if (identity != null && !WILDCARD.equals(identity)) {
            namedRoles.add(identity);
         }
         String area = key(header.getArea());
         String domain = key(header.getFunctionalDomain());
         String action = key(header.getAction());
         String where = "Policy " + policy.getRefName() + " rule '" + (rule.getName() == null ? "" : rule.getName()) + "'";
         if (area == null || WILDCARD.equals(area)) {
            out.add(new Diagnostic(Code.POLICY_WILDCARD_OUTSIDE_APPLICATION, vocabulary, where
               + " names area '*', which reaches beyond " + applicationId + "'s vocabulary."));
            continue;
         }
         Map<String, Set<String>> ownedArea = owned.get(area);
         if (ownedArea != null) {
            checkTriple(ownedArea, area, domain, action, where, vocabulary, out);
            continue;
         }
         Map<String, Set<String>> borrowedArea = borrowed.get(area);
         if (borrowedArea == null) {
            out.add(new Diagnostic(Code.POLICY_TRIPLE_UNDECLARED, vocabulary, where + " names area " + area
               + ", which " + applicationId + " neither owns nor uses."));
            continue;
         }
         if (WILDCARD.equals(domain)) {
            out.add(new Diagnostic(Code.POLICY_WILDCARD_OUTSIDE_APPLICATION, vocabulary, where
               + " names every domain of borrowed area " + area + "; name the used domains."));
            continue;
         }
         checkTriple(borrowedArea, area, domain, action, where, vocabulary, out);
         String role = identity != null && !WILDCARD.equals(identity) ? identity : principal;
         if (!declaredRoles.isEmpty() && (role == null || !declaredRoles.contains(role))) {
            out.add(new Diagnostic(Code.POLICY_BORROWED_FOREIGN_ROLE, vocabulary, where + " grants borrowed "
               + area + "/" + domain + " to '" + role + "', which is not a role of " + applicationId + "."));
         }
      }
   }

   private static void checkTriple(Map<String, Set<String>> areaDomains, String area, String domain, String action,
                                   String where, Severity vocabulary, List<Diagnostic> out) {
      if (domain == null || WILDCARD.equals(domain)) {
         return; // every domain of an area this application may name
      }
      Set<String> actions = areaDomains.get(domain);
      if (actions == null) {
         out.add(new Diagnostic(Code.POLICY_TRIPLE_UNDECLARED, vocabulary, where + " names " + area + "/" + domain
            + ", which is not declared."));
         return;
      }
      if (action != null && !WILDCARD.equals(action) && !actions.contains(action)) {
         out.add(new Diagnostic(Code.POLICY_TRIPLE_UNDECLARED, vocabulary, where + " names action " + action
            + " on " + area + "/" + domain + ", which declares " + actions + "."));
      }
   }

   private static Diagnostic error(Code code, String message) {
      return new Diagnostic(code, Severity.ERROR, message);
   }

   private static <T> List<T> nonNull(List<T> values) {
      return values == null ? List.of() : values;
   }

   /** Policy headers lowercase every axis, so vocabulary and roles compare case-insensitively. */
   private static String key(String value) {
      String trimmed = trim(value);
      return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
   }

   private static String trim(String value) {
      return value == null || value.isBlank() ? null : value.trim();
   }
}
