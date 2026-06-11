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

import java.util.List;

/**
 * Per-realm role assignment for a user (B4 identity model; realm-membership
 * ADR). A user has ONE global identity (their credential in the system realm)
 * and N realm-role assignments — the GitHub model: account → org memberships
 * with per-org roles. ADMIN in realm A, VIEWER in realm B is two documents.
 *
 * Consumed at token issuance (the IdP unions these into per-realm role
 * claims) and by the tenant-plane routing registry (JWT claims → realm
 * datastore). The credential's flat roles[] remains the user's roles in
 * their default realm (compatibility); these assignments extend, never
 * replace, that behavior.
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

   /** Roles the user holds IN THAT REALM (e.g. admin, user, viewer). */
   protected List<String> roles;

   /** Org within the realm that sponsored/invited this user (provenance). */
   protected String sponsoringOrgRefName;

   /** active | invited | suspended */
   protected String status;

   public static final String STATUS_ACTIVE = "active";
   public static final String STATUS_INVITED = "invited";
   public static final String STATUS_SUSPENDED = "suspended";

   @Override
   public String bmFunctionalArea() {
      return "SECURITY";
   }

   @Override
   public String bmFunctionalDomain() {
      return "USER_REALM_ROLE";
   }
}
