package com.e2eq.framework.model.security;

import com.e2eq.framework.model.persistent.base.BaseModel;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Field;
import dev.morphia.annotations.Index;
import dev.morphia.annotations.IndexOptions;
import dev.morphia.annotations.Indexes;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.Date;
import java.util.List;

/**
 * An {@link Application} installed for one tenant of a realm. A realm links to many applications
 * through its installs. Rows live in the system realm. Ids are caller-supplied when known, so a
 * migration can keep the ids of the records it replaces; an install written again with the same id
 * updates that row.
 *
 * <p>Only an {@link #STATUS_ACTIVE} install counts: a {@link UserAppRole} needs one for each of
 * its tenants, and resolution ignores tenants whose install is gone.
 */
@Entity("applicationInstall")
@RegisterForReflection
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Indexes({
   @Index(fields = {@Field("applicationId"), @Field("realmRefName"), @Field("tenantId")},
      options = @IndexOptions(unique = true, name = "uidx_application_install_active",
         partialFilter = "{ 'status': 'active' }"))
})
public class ApplicationInstall extends BaseModel {

   public static final String STATUS_ACTIVE = "active";
   public static final String STATUS_SUSPENDED = "suspended";
   public static final String STATUS_UNINSTALLED = "uninstalled";
   public static final List<String> STATUS_VALUES = List.of(STATUS_ACTIVE, STATUS_SUSPENDED, STATUS_UNINSTALLED);

   /** The tenant the application is installed for. */
   protected String tenantId;

   /** The {@link Application} refName. */
   protected String applicationId;

   /** The realm holding the tenant's data. */
   protected String realmRefName;

   /** One of {@link #STATUS_VALUES}. */
   protected String status;

   protected String installedBy;
   protected Date installedAt;
   protected String uninstalledBy;
   protected Date uninstalledAt;

   public boolean active() {
      return STATUS_ACTIVE.equals(status);
   }

   @Override
   public String bmFunctionalArea() {
      return "SECURITY";
   }

   @Override
   public String bmFunctionalDomain() {
      return "APPLICATION_INSTALL";
   }
}
