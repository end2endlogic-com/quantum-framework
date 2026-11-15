package com.e2eq.framework.model.security;

import com.e2eq.framework.model.persistent.base.BaseModel;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Field;
import dev.morphia.annotations.Index;
import dev.morphia.annotations.Indexes;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * An identity's access to one application in one realm: the application binding of the accepted
 * application-binding design, named {@code UserAppRole}. One row per (subject, application,
 * realm), where the subject is a user ({@link #userId}) or a {@link UserGroup}
 * ({@link #groupRefName}), never both. Rows live in the system realm with credentials and
 * {@link UserRealmRole}.
 *
 * <ul>
 *   <li>{@link #roles} must be roles the {@link Application} declares;</li>
 *   <li>{@link #tenantIds} are the tenants of the realm the binding grants; more than one needs a
 *       {@link #defaultTenantId} among them;</li>
 *   <li>the application must be installed ({@link ApplicationInstall}) for every tenant.</li>
 * </ul>
 *
 * <p>{@link UserRealmRole} stays the realm membership. Resolution reads the binding first and
 * falls back to the membership's application grant only when no binding exists; realm-wide
 * membership roles still apply in every application. {@link ApplicationBindingValidator} holds
 * the rules every write path applies.
 */
@Entity("userAppRole")
@RegisterForReflection
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Indexes({
   @Index(fields = {@Field("userId"), @Field("applicationId"), @Field("realmRefName")}),
   @Index(fields = {@Field("groupRefName"), @Field("applicationId"), @Field("realmRefName")})
})
public class UserAppRole extends BaseModel {

   public static final String STATUS_ACTIVE = "active";
   public static final String STATUS_SUSPENDED = "suspended";
   public static final List<String> STATUS_VALUES = List.of(STATUS_ACTIVE, STATUS_SUSPENDED);

   public static final String SOURCE_PROVISIONING = "provisioning";
   public static final String SOURCE_INVITE = "invite";
   public static final String SOURCE_TENANT_ADMIN = "tenant-admin";
   public static final String SOURCE_STAFF = "staff";
   public static final String SOURCE_GROUP = "group";
   public static final String SOURCE_MIGRATION = "migration";
   public static final List<String> SOURCE_VALUES = List.of(SOURCE_PROVISIONING, SOURCE_INVITE,
      SOURCE_TENANT_ADMIN, SOURCE_STAFF, SOURCE_GROUP, SOURCE_MIGRATION);

   /** Prefix of {@link #suspendedBy} for bindings an uninstall suspended, followed by the install id. */
   public static final String SUSPENDED_BY_UNINSTALL = "uninstall:";

   /** The user's global identity (credential userId); null for a group binding. */
   protected String userId;

   /** The {@link UserGroup} refName (system realm); null for a user binding. */
   protected String groupRefName;

   /** The {@link Application} refName. */
   protected String applicationId;

   /** The realm the binding applies in. */
   protected String realmRefName;

   /** Roles the application declares; nothing else is accepted. */
   protected List<String> roles = new ArrayList<>();

   /** Tenants of the realm the binding grants. */
   protected List<String> tenantIds = new ArrayList<>();

   /** The tenant used when none is selected; required when {@link #tenantIds} has several. */
   protected String defaultTenantId;

   /** This binding's realm is the one used for the application when a request names none. */
   protected Boolean defaultRealm;

   /** {@link #STATUS_ACTIVE} (or unset) grants; {@link #STATUS_SUSPENDED} refuses. */
   protected String status;

   /**
    * Why a suspended binding is suspended, set by the server: {@link #SUSPENDED_BY_UNINSTALL} and
    * the install id when an uninstall suspended it (a reinstall reactivates exactly those), else
    * {@code user:} and the writer. Null while active.
    */
   protected String suspendedBy;

   /** Who created the binding; one of {@link #SOURCE_VALUES}. Provenance only. */
   protected String source;

   protected String grantedBy;
   protected Date grantedAt;

   public boolean groupBinding() {
      return groupRefName != null && !groupRefName.isBlank();
   }

   /** The group refName, trimmed, or null for a user binding. */
   public String groupRefName() {
      return groupBinding() ? groupRefName.trim() : null;
   }

   public boolean active() {
      return status == null || STATUS_ACTIVE.equals(status);
   }

   @Override
   public String bmFunctionalArea() {
      return "SECURITY";
   }

   @Override
   public String bmFunctionalDomain() {
      return "USER_APP_ROLE";
   }
}
