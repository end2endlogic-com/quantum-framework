package com.e2eq.framework.model.security;

import com.e2eq.framework.model.persistent.base.BaseModel;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Index;
import dev.morphia.annotations.Indexes;
import dev.morphia.annotations.Field;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Per-realm role assignment for a user (B4 identity model; realm-membership
 * ADR). A user has ONE global identity (their credential in the system realm)
 * and N realm-role assignments — the GitHub model: account → org memberships
 * with per-org roles. ADMIN in realm A, VIEWER in realm B is two documents.
 *
 * Consumed at token issuance (the IdP unions these into per-realm role
 * claims) and by the tenant-plane routing registry (JWT claims → realm
 * datastore). The credential remains the authoritative realm-access boundary:
 * an assignment can supply roles and application grants inside an authorized
 * realm, but must never authorize a realm excluded by the credential's
 * authorizedRealms/realmRegEx contract.
 *
 * Role scope: the credential's flat roles[] apply in every realm the user can
 * access (by design). A UserRealmRole adds roles within its one realm only.
 * Roles that must hold in a single realm (for example roles granted by an
 * access invite) belong here, never on the credential's flat roles.
 *
 * Data domain: the assignment's own dataDomain is the user's default partition
 * inside the realm. In a pooled realm, tenant selection builds the request
 * partition from the tenant's RealmTenantMembership and, when this data domain
 * is for the same tenant/org/account, carries over its dataSegment, locationId
 * and businessTransactionId. An access invite accepted under the
 * pooled-realm-tenant policy writes the inviter's partition here (owner = the
 * invitee) for a new assignment and unions the tenant into authorizedTenantIds;
 * an existing assignment keeps its default partition.
 *
 * There is at most one assignment per (userId, realmRefName). The unique index
 * enforcing it is created by UserRealmRoleRepo.ensureUniqueAssignmentIndex
 * (not declared here, so existing duplicates cannot break datastore start-up).
 */
@Entity
@RegisterForReflection
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Indexes({
    @Index(fields = {@Field("userId"), @Field("realmRefName")})
})
public class UserRealmRole extends BaseModel {

   /** The user's global identity (credential userId in the system realm). */
   protected String userId;

   /** Optional stable subject identifier when it differs from userId. */
   protected String subject;

   /** The realm this assignment applies to. */
   protected String realmRefName;

   /**
    * Roles the user holds IN THAT REALM (e.g. admin, user, viewer). Never null: an assignment
    * that grants no roles (for example an invite that only authorizes applications) holds an
    * empty list. Morphia does not store empty lists, so such a row is read back without the
    * field, and older rows may carry an explicit null; {@link #getRoles()} reads both as empty
    * and {@link #setRoles(List)} writes null as empty, so the directory contract
    * ({@code UserRealmRoleEntry.roles} is required) always holds.
    */
   @lombok.Builder.Default
   protected List<String> roles = new ArrayList<>();

   /**
    * Applications this user may authenticate for IN THIS REALM (application-scoped
    * auth; nested (realm, application) grant). Values are {@code ApplicationDefinition.refName}s
    * (the app registry lives in the enterprise layer, so these are held as plain
    * refName strings here, not a typed reference). Semantics:
    * <ul>
    *   <li>{@code null}/empty — NOT configured: legacy behavior (single default
    *       audience, no app scoping). Backward compatible.</li>
    *   <li>a concrete list — the token's {@code aud} is the whole set (multi-aud
    *       SSO across the suite); the active app is resolved into {@code azp}.</li>
    *   <li>{@code "*"} — admin-only, audited "any app" grant. Because the installed
    *       app set isn't enumerable from this layer, a {@code *} grant requires the
    *       caller to name the applicationId; the token is scoped to that app.</li>
    * </ul>
    */
   protected List<String> authorizedApplications;

   /**
    * The app to assume when the caller doesn't pass an applicationId and more than
    * one is authorized. Must be one of {@link #authorizedApplications}. Optional.
    */
   protected String defaultApplication;

   /**
    * Tenants this user may select inside this realm. Required for explicit
    * tenant selection in a MULTI_TENANT realm. A null/empty value does not
    * grant access; legacy single-tenant realms continue to use the realm's
    * default DomainContext.
    */
   protected List<String> authorizedTenantIds;

   /**
    * Optional full-string, case-insensitive tenant entitlement expression for
    * shared realms. Explicit tenant ids and this expression are unioned; an
    * invalid expression grants nothing. Dedicated realms ignore this field and
    * expose only the tenant bound to the realm.
    */
   protected String authorizedTenantRegEx;

   public static final String APPLICATION_WILDCARD = "*";

   /** Org within the realm that sponsored/invited this user (provenance). */
   protected String sponsoringOrgRefName;

   /** active | invited | suspended */
   protected String status;

   /**
    * Tenants, among {@link #authorizedTenantIds}, in which this user is an outsider
    * rather than a member. Absent or empty means member in every authorized tenant.
    */
   protected List<String> externalTenantIds;

   public static final String STATUS_ACTIVE = "active";
   public static final String STATUS_INVITED = "invited";
   public static final String STATUS_SUSPENDED = "suspended";

   /** Platform-reserved role held by a principal finalized as an outsider in the selected tenant. */
   public static final String ROLE_EXTERNAL = "external";

   /** Preserves the pre-externalTenantIds 10-argument constructor signature. */
   public UserRealmRole(String userId, String subject, String realmRefName, List<String> roles,
                        List<String> authorizedApplications, String defaultApplication,
                        List<String> authorizedTenantIds, String authorizedTenantRegEx,
                        String sponsoringOrgRefName, String status) {
      this(userId, subject, realmRefName, roles, authorizedApplications, defaultApplication,
          authorizedTenantIds, authorizedTenantRegEx, sponsoringOrgRefName, status, null);
   }

   /** The realm roles; an absent or null stored value reads as an empty list, never null. */
   public List<String> getRoles() {
      if (roles == null) {
         roles = new ArrayList<>();
      }
      return roles;
   }

   /** Sets the realm roles; null is written as an empty list (no roles), never null. */
   public void setRoles(List<String> roles) {
      this.roles = roles == null ? new ArrayList<>() : roles;
   }

   /** True when this assignment marks {@code tenantId} as an external (outsider) tenant. */
   public boolean externalInTenant(String tenantId) {
      if (tenantId == null || tenantId.isBlank() || externalTenantIds == null || externalTenantIds.isEmpty()) {
         return false;
      }
      String normalized = tenantId.trim();
      return externalTenantIds.stream()
          .filter(Objects::nonNull)
          .map(String::trim)
          .anyMatch(normalized::equals);
   }

   /** True when {@code role} names the platform-reserved {@link #ROLE_EXTERNAL} role (case-insensitive). */
   public static boolean isReservedExternalRole(String role) {
      return role != null && ROLE_EXTERNAL.equalsIgnoreCase(role.trim());
   }

   /** Refuses any persisted role list that attempts to grant {@link #ROLE_EXTERNAL}. */
   public static void requireNoExternalRole(Collection<String> roles, String recordType) {
      if (roles == null) {
         return;
      }
      for (String role : roles) {
         if (isReservedExternalRole(role)) {
            throw new IllegalArgumentException(
                "Role '" + ROLE_EXTERNAL + "' belongs to the platform and cannot be granted on " + recordType);
         }
      }
   }

   /** Refuses any persisted role array that attempts to grant {@link #ROLE_EXTERNAL}. */
   public static void requireNoExternalRole(String[] roles, String recordType) {
      if (roles == null) {
         return;
      }
      for (String role : roles) {
         if (isReservedExternalRole(role)) {
            throw new IllegalArgumentException(
                "Role '" + ROLE_EXTERNAL + "' belongs to the platform and cannot be granted on " + recordType);
         }
      }
   }

   /** Refuses any field-update payload (collection, array, or scalar) that attempts to grant {@link #ROLE_EXTERNAL}. */
   public static void requireNoExternalRoleValue(Object value, String recordType) {
      if (value == null) {
         return;
      }
      if (value instanceof String[] array) {
         requireNoExternalRole(array, recordType);
         return;
      }
      if (value instanceof Iterable<?> iterable) {
         for (Object item : iterable) {
            if (item != null && isReservedExternalRole(String.valueOf(item))) {
               throw new IllegalArgumentException(
                   "Role '" + ROLE_EXTERNAL + "' belongs to the platform and cannot be granted on " + recordType);
            }
         }
         return;
      }
      if (isReservedExternalRole(String.valueOf(value))) {
         throw new IllegalArgumentException(
             "Role '" + ROLE_EXTERNAL + "' belongs to the platform and cannot be granted on " + recordType);
      }
   }

   @Override
   public void validate() {
      super.validate();
      requireNoExternalRole(getRoles(), "UserRealmRole");
      if (externalTenantIds != null) {
         for (String tenantId : externalTenantIds) {
            if (tenantId == null || tenantId.isBlank()) {
               throw new IllegalArgumentException("externalTenantIds must not contain null or blank entries");
            }
         }
      }
   }

   @Override
   public String bmFunctionalArea() {
      return "SECURITY";
   }

   @Override
   public String bmFunctionalDomain() {
      return "USER_REALM_ROLE";
   }
}
