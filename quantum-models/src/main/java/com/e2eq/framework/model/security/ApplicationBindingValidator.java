package com.e2eq.framework.model.security;

import com.e2eq.framework.model.security.ApplicationAccessException.Code;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The rules every write of a {@link UserAppRole} applies, and that resolution re-applies to the
 * rows it reads (a row that breaks them fails closed). Pure: the caller supplies the application
 * and the install lookup.
 */
public final class ApplicationBindingValidator {

   /**
    * Never grantable through a binding, whatever an application's {@code grantableBy} says,
    * compared without case. Binding roles join the principal's global roles, so a bound name that
    * a {@code @RolesAllowed} check or the platform recognizes would grant platform authority: the
    * platform's own roles ({@link ApplicationDeclarationValidator#RESERVED_ROLES}), the platform
    * and tenant administrator roles, and the anonymous marker. A deployment's configured
    * membership-administrator roles are reserved too; callers pass them as {@code alsoReserved}.
    */
   public static final Set<String> RESERVED_ROLES = Set.copyOf(java.util.stream.Stream.concat(
         ApplicationDeclarationValidator.RESERVED_ROLES.stream(),
         java.util.stream.Stream.of("platformadmin", "tenantadmin", "anonymous"))
      .collect(Collectors.toSet()));

   /**
    * Who is writing a binding, from the existing framework administrator definitions: the system
    * principal, a realm administrator, or a membership administrator of the tenant.
    */
   public enum Authority {
      PLATFORM,
      REALM_ADMIN,
      TENANT_ADMIN,
      NONE
   }

   private ApplicationBindingValidator() {
   }

   /**
    * Structural rules: one subject, application and realm named, known status and source, roles
    * the application declares and none of the platform's reserved roles, at least one tenant, a default among several, and an install for
    * every tenant.
    *
    * @param application the registered application, or null when none is registered
    * @param installed   whether the application is installed (active) for a tenant of the binding's realm
    */
   public static void validate(UserAppRole binding, Application application, Predicate<String> installed) {
      validate(binding, application, installed, Set.of());
   }

   /**
    * {@link #validate(UserAppRole, Application, Predicate)} with {@code alsoReserved} (the
    * deployment's membership-administrator roles) refused like {@link #RESERVED_ROLES}. A
    * suspended binding grants nothing, so it is saved without an install: an uninstall's
    * suspended bindings stay editable until a reinstall reactivates them.
    */
   public static void validate(UserAppRole binding, Application application, Predicate<String> installed,
                               Collection<String> alsoReserved) {
      Objects.requireNonNull(binding, "binding");
      boolean user = nonBlank(binding.getUserId());
      boolean group = nonBlank(binding.getGroupRefName());
      if (user == group) {
         throw new ApplicationAccessException(Code.BINDING_SUBJECT_INVALID,
            "a binding names exactly one subject: a userId or a groupRefName");
      }
      if (!nonBlank(binding.getApplicationId()) || !nonBlank(binding.getRealmRefName())) {
         throw new ApplicationAccessException(Code.BINDING_INVALID, "applicationId and realmRefName are required");
      }
      if (binding.getStatus() != null && !UserAppRole.STATUS_VALUES.contains(binding.getStatus())) {
         throw new ApplicationAccessException(Code.BINDING_INVALID,
            "status '" + binding.getStatus() + "' is not one of " + UserAppRole.STATUS_VALUES);
      }
      if (binding.getSource() != null && !UserAppRole.SOURCE_VALUES.contains(binding.getSource())) {
         throw new ApplicationAccessException(Code.BINDING_INVALID,
            "source '" + binding.getSource() + "' is not one of " + UserAppRole.SOURCE_VALUES);
      }
      if (application == null || !binding.getApplicationId().trim().equals(application.getRefName())) {
         throw new ApplicationAccessException(Code.APPLICATION_UNKNOWN,
            "application '" + binding.getApplicationId() + "' is not registered");
      }
      Set<String> declared = declaredRoles(application).keySet();
      for (String role : nonNull(binding.getRoles())) {
         requireNotReserved(role, alsoReserved);
         if (!nonBlank(role) || !declared.contains(role.trim())) {
            throw new ApplicationAccessException(Code.ROLE_NOT_DECLARED, String.format(
               "role '%s' is not declared by application '%s' (declared: %s)",
               role, application.getRefName(), declared));
         }
      }
      List<String> tenants = tenants(binding);
      if (tenants.isEmpty() || tenants.size() != nonNull(binding.getTenantIds()).size()) {
         throw new ApplicationAccessException(Code.TENANTS_REQUIRED,
            "a binding names one or more distinct, non-blank tenants");
      }
      String defaultTenant = trim(binding.getDefaultTenantId());
      if (defaultTenant != null && !tenants.contains(defaultTenant)) {
         throw new ApplicationAccessException(Code.TENANT_DEFAULT_REQUIRED,
            "default tenant '" + defaultTenant + "' is not one of the binding's tenants " + tenants);
      }
      if (tenants.size() > 1 && defaultTenant == null) {
         throw new ApplicationAccessException(Code.TENANT_DEFAULT_REQUIRED,
            "a binding for several tenants " + tenants + " needs a defaultTenantId among them");
      }
      Objects.requireNonNull(installed, "installed");
      if (UserAppRole.STATUS_SUSPENDED.equals(binding.getStatus())) {
         return;
      }
      for (String tenant : tenants) {
         if (!installed.test(tenant)) {
            throw new ApplicationAccessException(Code.APPLICATION_NOT_INSTALLED, String.format(
               "application '%s' is not installed for tenant '%s' in realm '%s'",
               application.getRefName(), tenant, binding.getRealmRefName()));
         }
      }
   }

   /**
    * Each role's {@link ApplicationRole#getGrantableBy()} decides: the platform grants any declared
    * role, a realm administrator those grantable by realm-admin or tenant-admin, a tenant
    * administrator those grantable by tenant-admin. The platform's reserved roles never are.
    */
   public static void requireGrantable(Application application, Collection<String> roles, Authority authority) {
      requireGrantable(application, roles, authority, Set.of());
   }

   /** {@link #requireGrantable(Application, Collection, Authority)} with {@code alsoReserved} refused too. */
   public static void requireGrantable(Application application, Collection<String> roles, Authority authority,
                                       Collection<String> alsoReserved) {
      Map<String, ApplicationRole> declared = declaredRoles(application);
      for (String role : roles == null ? List.<String>of() : roles) {
         String name = trim(role);
         if (name == null) {
            throw new ApplicationAccessException(Code.ROLE_NOT_GRANTABLE, "role '" + role + "' is never grantable");
         }
         requireNotReserved(name, alsoReserved);
         ApplicationRole declaredRole = declared.get(name);
         if (declaredRole == null) {
            throw new ApplicationAccessException(Code.ROLE_NOT_DECLARED,
               "role '" + name + "' is not declared by application '" + application.getRefName() + "'");
         }
         if (!grantable(declaredRole, authority)) {
            throw new ApplicationAccessException(Code.ROLE_NOT_GRANTABLE, String.format(
               "role '%s' of application '%s' is grantable by %s; the caller is %s",
               name, application.getRefName(), nonNull(declaredRole.getGrantableBy()), authority));
         }
      }
   }

   /** Whether {@code role} is reserved: one of {@link #RESERVED_ROLES} or {@code alsoReserved}, without case. */
   public static boolean reserved(String role, Collection<String> alsoReserved) {
      if (role == null || role.isBlank()) {
         return false;
      }
      String name = role.trim().toLowerCase(Locale.ROOT);
      return RESERVED_ROLES.contains(name) || (alsoReserved != null && alsoReserved.stream()
         .filter(Objects::nonNull).map(value -> value.trim().toLowerCase(Locale.ROOT)).anyMatch(name::equals));
   }

   private static void requireNotReserved(String role, Collection<String> alsoReserved) {
      if (reserved(role, alsoReserved)) {
         throw new ApplicationAccessException(Code.ROLE_NOT_GRANTABLE,
            "role '" + role.trim() + "' belongs to the platform and is never granted by a binding");
      }
   }

   static boolean grantable(ApplicationRole role, Authority authority) {
      if (authority == Authority.PLATFORM) {
         return true;
      }
      Set<String> by = nonNull(role.getGrantableBy()).stream()
         .filter(Objects::nonNull).map(value -> value.trim().toLowerCase(Locale.ROOT))
         .collect(Collectors.toSet());
      return switch (authority == null ? Authority.NONE : authority) {
         case REALM_ADMIN -> by.contains(ApplicationRole.GRANTABLE_BY_REALM_ADMIN)
            || by.contains(ApplicationRole.GRANTABLE_BY_TENANT_ADMIN);
         case TENANT_ADMIN -> by.contains(ApplicationRole.GRANTABLE_BY_TENANT_ADMIN);
         default -> false;
      };
   }

   /** The binding's tenants, trimmed, without blanks or duplicates. */
   public static List<String> tenants(UserAppRole binding) {
      LinkedHashSet<String> out = new LinkedHashSet<>();
      for (String tenant : nonNull(binding.getTenantIds())) {
         String value = trim(tenant);
         if (value != null) {
            out.add(value);
         }
      }
      return List.copyOf(out);
   }

   static Map<String, ApplicationRole> declaredRoles(Application application) {
      return nonNull(application.getRoles()).stream()
         .filter(Objects::nonNull)
         .filter(role -> nonBlank(role.getName()))
         .collect(Collectors.toMap(role -> role.getName().trim(), Function.identity(), (left, right) -> left));
   }

   private static <T> List<T> nonNull(List<T> values) {
      return values == null ? List.of() : values;
   }

   private static boolean nonBlank(String value) {
      return value != null && !value.isBlank();
   }

   private static String trim(String value) {
      return value == null || value.isBlank() ? null : value.trim();
   }
}
