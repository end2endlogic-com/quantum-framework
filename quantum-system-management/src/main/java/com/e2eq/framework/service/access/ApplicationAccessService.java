package com.e2eq.framework.service.access;

import com.e2eq.framework.model.persistent.morphia.ApplicationAccessDirectory;
import com.e2eq.framework.model.persistent.morphia.ApplicationInstallRepo;
import com.e2eq.framework.model.persistent.morphia.ApplicationRepo;
import com.e2eq.framework.model.persistent.morphia.UserAppRoleRepo;
import com.e2eq.framework.model.persistent.morphia.UserGroupRepo;
import com.e2eq.framework.model.security.Application;
import com.e2eq.framework.model.security.ApplicationAccessException;
import com.e2eq.framework.model.security.ApplicationAccessException.Code;
import com.e2eq.framework.model.security.ApplicationBindingValidator;
import com.e2eq.framework.model.security.ApplicationBindingValidator.Authority;
import com.e2eq.framework.model.security.ApplicationInstall;
import com.e2eq.framework.model.security.ApplicationTenancy;
import com.e2eq.framework.model.security.UserAppRole;
import com.e2eq.framework.model.security.UserGroup;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.util.EnvConfigUtils;
import com.e2eq.framework.util.SecurityUtils;
import io.quarkus.logging.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Writes and reads of application bindings ({@link UserAppRole}) and installs
 * ({@link ApplicationInstall}) for a caller. The realm is the caller's resolved realm; only the
 * platform (the system principal) may name another. Who may write is decided by the existing
 * administrator definitions ({@link AccessInviteService#administrativeAuthority}) and each role's
 * {@code grantableBy}; the records' own rules are applied by their repositories on every save.
 * Installs and uninstalls need the platform or a realm administrator of the tenant, with one
 * narrow exception: an application's own service identity may install and uninstall that same
 * application in a realm the application declares ({@link #declaredRealms}). Under a remote
 * directory every call refuses with {@code DIRECTORY_REMOTE}: the rows live, and are written, at
 * the directory owner.
 */
@ApplicationScoped
public class ApplicationAccessService {

   @ConfigProperty(name = "quantum.security.policy-scope.require-application", defaultValue = "false")
   boolean requireApplicationScope;

   @Inject
   AccessInviteService accessInviteService;

   @Inject
   UserAppRoleRepo userAppRoleRepo;

   @Inject
   ApplicationInstallRepo applicationInstallRepo;

   @Inject
   ApplicationRepo applicationRepo;

   @Inject
   UserGroupRepo userGroupRepo;

   @Inject
   ApplicationAccessDirectory accessDirectory;

   @Inject
   SecurityUtils securityUtils;

   @Inject
   EnvConfigUtils envConfigUtils;

   // ------------------------------------------------------------------ bindings

   /**
    * Creates or replaces a binding. The existing row is found by id, else by (subject,
    * application, realm); the caller must be allowed both the binding it replaces and the new one.
    */
   public UserAppRole putBinding(String callerRealm, UserAppRole request) {
      Objects.requireNonNull(request, "binding");
      accessDirectory.requireLocalDirectory();
      Authority probe = authorityFor(callerRealm, List.of());
      String realm = realmFor(callerRealm, request.getRealmRefName(), probe);
      request.setRealmRefName(realm);
      Application application = application(request.getApplicationId());
      boolean user = request.getUserId() != null && !request.getUserId().isBlank();
      if (user == (request.groupRefName() != null)) {
         throw new ApplicationAccessException(Code.BINDING_SUBJECT_INVALID,
            "a binding names exactly one subject: a userId or a groupRefName");
      }
      if (request.groupRefName() != null) {
         requireGroupBindable(request, probe);
      }

      Optional<UserAppRole> existing = system(() -> stored(request));
      existing.ifPresent(current -> {
         // A binding of another realm reads exactly like a missing one, so ids cannot be probed.
         if (!Objects.equals(current.getRealmRefName(), realm)) {
            throw new ApplicationAccessException(Code.NOT_FOUND, "no binding " + current.getId());
         }
         // A binding id keeps its subject and application, as an install id keeps its tenant:
         // rewriting one by id must not hand another identity's binding (and its defaultRealm) over.
         if (!Objects.equals(trimmed(current.getUserId()), trimmed(request.getUserId()))
               || !Objects.equals(current.groupRefName(), request.groupRefName())
               || !Objects.equals(trimmed(current.getApplicationId()), trimmed(request.getApplicationId()))) {
            throw new ApplicationAccessException(Code.BINDING_SUBJECT_INVALID, String.format(
               "binding %s is %s's for application '%s'; a binding's subject and application never change",
               current.getId(), subject(current), current.getApplicationId()));
         }
         requireMayGrant(realm, application, current);
      });
      requireMayGrant(realm, application, request);

      PrincipalContext caller = SecurityContext.getPrincipalContext().orElseThrow();
      requireDefaultRealmSetter(request, existing.orElse(null), caller, probe);
      if (!request.groupBinding() && !accessInviteService.hasActiveRealmMembership(request.getUserId(), realm)) {
         throw new ApplicationAccessException(Code.BINDING_SUBJECT_INVALID, String.format(
            "%s has no active membership in realm '%s'; only members are bound", request.getUserId().trim(), realm));
      }

      request.setId(existing.map(UserAppRole::getId).orElse(request.getId()));
      request.setGrantedBy(caller.getUserId());
      request.setGrantedAt(new Date());
      // Provenance is the server's: whatever the request says, the writer's authority decides.
      request.setSource(probe == Authority.PLATFORM ? UserAppRole.SOURCE_STAFF : UserAppRole.SOURCE_TENANT_ADMIN);
      if (request.getStatus() == null) {
         request.setStatus(existing.map(UserAppRole::getStatus).orElse(UserAppRole.STATUS_ACTIVE));
      }
      request.setSuspendedBy(suspendedBy(request, existing.orElse(null), caller));
      UserAppRole saved = system(() -> request.getId() == null
         ? userAppRoleRepo.save(request)
         : userAppRoleRepo.upsertById(request));
      Log.infof("application-binding.put id=%s application=%s realm=%s subject=%s by=%s",
         saved.getId(), saved.getApplicationId(), saved.getRealmRefName(), subject(saved), caller.getUserId());
      return saved;
   }

   public void deleteBinding(String callerRealm, ObjectId id) {
      accessDirectory.requireLocalDirectory();
      Authority probe = authorityFor(callerRealm, List.of());
      // A binding of another realm reads exactly like a missing one, so ids cannot be probed.
      UserAppRole binding = system(() -> userAppRoleRepo.findByIdWithIgnoreRules(id))
         .filter(found -> probe == Authority.PLATFORM || Objects.equals(found.getRealmRefName(), callerRealm))
         .orElseThrow(() -> new ApplicationAccessException(Code.NOT_FOUND, "no binding " + id));
      realmFor(callerRealm, binding.getRealmRefName(), probe);
      requireMayGrant(binding.getRealmRefName(), application(binding.getApplicationId()), binding);
      system(() -> {
         try {
            return userAppRoleRepo.delete(binding);
         } catch (com.e2eq.framework.exceptions.ReferentialIntegrityViolationException e) {
            throw new IllegalStateException(e);
         }
      });
      Log.infof("application-binding.delete id=%s application=%s realm=%s subject=%s", id,
         binding.getApplicationId(), binding.getRealmRefName(), subject(binding));
   }

   /**
    * The bindings for an application in the caller's realm that name {@code tenantId}, which the
    * caller administers. Each row shows only the tenants the caller administers: a binding that
    * also grants another tenant does not reveal it (nor a default tenant outside them).
    */
   public List<UserAppRole> listBindings(String callerRealm, String applicationId, String tenantId) {
      accessDirectory.requireLocalDirectory();
      String tenant = tenantOf(tenantId);
      if (authorityFor(callerRealm, List.of(tenant)) == Authority.NONE) {
         throw new ApplicationAccessException(Code.ROLE_NOT_GRANTABLE,
            "only an administrator of tenant '" + tenant + "' may list its bindings");
      }
      application(applicationId);
      List<UserAppRole> rows = system(() ->
         userAppRoleRepo.findByApplicationWithIgnoreRules(applicationId.trim(), callerRealm, tenant));
      if (isPlatform()) {
         return rows;
      }
      java.util.Map<String, Boolean> administered = new java.util.HashMap<>();
      List<UserAppRole> visible = new ArrayList<>();
      for (UserAppRole row : rows) {
         List<String> tenants = ApplicationBindingValidator.tenants(row).stream()
            .filter(candidate -> administered.computeIfAbsent(candidate,
               key -> authorityFor(callerRealm, List.of(key)) != Authority.NONE))
            .toList();
         if (tenants.isEmpty()) {
            continue;
         }
         row.setTenantIds(new ArrayList<>(tenants));
         if (row.getDefaultTenantId() != null && !tenants.contains(row.getDefaultTenantId().trim())) {
            row.setDefaultTenantId(null);
         }
         visible.add(row);
      }
      return visible;
   }

   // ------------------------------------------------------------------ installs

   /**
    * Installs an application for a tenant of the caller's realm. With an id the install is written
    * under it (a repeated install with the same id changes nothing); without one an existing active
    * install for the tenant is returned as is.
    *
    * <p>An id that names a stored install is a write to that install: it must be in the caller's
    * realm (any realm for the platform), the caller needs install authority over the stored tenant
    * as well as the requested one, and its realm, tenant and application never change.
    */
   public ApplicationInstall install(String callerRealm, ApplicationInstall request) {
      Objects.requireNonNull(request, "install");
      accessDirectory.requireLocalDirectory();
      Authority probe = authorityFor(callerRealm, List.of());
      String ownApplication = probe == Authority.PLATFORM ? null : ownServiceApplication(request.getApplicationId());
      String realm;
      if (ownApplication != null) {
         realm = declaredRealm(application(ownApplication), request.getRealmRefName());
         requireTenant(request.getTenantId());
         // A caller-supplied id is a write to whatever install holds it, and the stored row is read and
         // replaced in separate steps; a service identity installs without one (an existing active
         // install is returned), so it can never reach another install by id.
         if (request.getId() != null) {
            throw new ApplicationAccessException(Code.INSTALL_NOT_AUTHORIZED,
               "a service identity installs without an id; the active install is returned when there is one");
         }
      } else {
         realm = realmFor(callerRealm, request.getRealmRefName(), probe);
         application(request.getApplicationId());
         requireInstallAuthority(realm, request.getTenantId());
      }
      request.setRealmRefName(realm);
      if (request.getId() != null) {
         Optional<ApplicationInstall> stored = system(() -> applicationInstallRepo.findByIdWithIgnoreRules(request.getId()));
         if (stored.isPresent()) {
            ApplicationInstall current = stored.get();
            if (probe != Authority.PLATFORM && !Objects.equals(current.getRealmRefName(), callerRealm)) {
               throw new ApplicationAccessException(Code.REALM_MISMATCH,
                  "install " + request.getId() + " is not in the caller's realm '" + callerRealm + "'");
            }
            requireInstallAuthority(current.getRealmRefName(), current.getTenantId());
            if (!Objects.equals(current.getRealmRefName(), realm)
               || !Objects.equals(current.getTenantId(), request.getTenantId().trim())
               || !Objects.equals(current.getApplicationId(), request.getApplicationId().trim())) {
               throw new ApplicationAccessException(Code.INSTALL_INVALID, String.format(
                  "install %s is for application '%s', tenant '%s' in realm '%s'; those never change",
                  current.getId(), current.getApplicationId(), current.getTenantId(), current.getRealmRefName()));
            }
         }
      }
      if (request.getId() == null) {
         Optional<ApplicationInstall> active = system(() -> applicationInstallRepo.findActiveWithIgnoreRules(
            request.getApplicationId().trim(), realm, request.getTenantId().trim()));
         if (active.isPresent()) {
            return active.get();
         }
      }
      PrincipalContext caller = SecurityContext.getPrincipalContext().orElseThrow();
      // Bindings an uninstall of this application for this tenant suspended come back with it.
      List<ObjectId> uninstalled = system(() -> applicationInstallRepo.findUninstalledIdsWithIgnoreRules(
         request.getApplicationId().trim(), realm, request.getTenantId().trim()));
      request.setStatus(ApplicationInstall.STATUS_ACTIVE);
      request.setInstalledBy(caller.getUserId());
      request.setInstalledAt(new Date());
      request.setUninstalledBy(null);
      request.setUninstalledAt(null);
      ApplicationInstall saved = system(() -> request.getId() == null
         ? applicationInstallRepo.save(request)
         : applicationInstallRepo.upsertById(request));
      long reactivated = system(() -> userAppRoleRepo.reactivateGroupBindingsAfterReinstall(
         saved.getApplicationId(), saved.getRealmRefName(), saved.getTenantId(), uninstalled));
      Log.infof("application-install.install id=%s application=%s realm=%s tenant=%s by=%s groupBindingsReactivated=%d",
         saved.getId(), saved.getApplicationId(), saved.getRealmRefName(), saved.getTenantId(), caller.getUserId(),
         reactivated);
      return saved;
   }

   /**
    * Uninstalls: the install is kept with status uninstalled, and the application's active group
    * bindings for that tenant are suspended with {@code suspendedBy = uninstall:<installId>},
    * whoever wrote them; a reinstall of the application for the tenant reactivates exactly those.
    * Nothing is deleted. User bindings stay but stop granting the tenant.
    */
   public ApplicationInstall uninstall(String callerRealm, ObjectId id) {
      accessDirectory.requireLocalDirectory();
      Authority probe = authorityFor(callerRealm, List.of());
      boolean service = probe != Authority.PLATFORM && callerIsServiceIdentity();
      // An install of another realm reads exactly like a missing one, so ids cannot be probed. For an
      // application's service identity, so does any install but its own application's in a realm
      // that application declares.
      ApplicationInstall stored = system(() -> applicationInstallRepo.findByIdWithIgnoreRules(id))
         .filter(found -> probe == Authority.PLATFORM
            || (service ? ownDeclaredInstall(found) : Objects.equals(found.getRealmRefName(), callerRealm)))
         .orElseThrow(() -> new ApplicationAccessException(Code.NOT_FOUND, "no install " + id));
      if (!service) {
         requireInstallAuthority(stored.getRealmRefName(), stored.getTenantId());
      }
      ApplicationInstall uninstalled = stored;
      boolean changed = !ApplicationInstall.STATUS_UNINSTALLED.equals(stored.getStatus());
      if (changed) {
         PrincipalContext caller = SecurityContext.getPrincipalContext().orElseThrow();
         stored.setStatus(ApplicationInstall.STATUS_UNINSTALLED);
         stored.setUninstalledBy(caller.getUserId());
         stored.setUninstalledAt(new Date());
         uninstalled = system(() -> applicationInstallRepo.upsertById(stored));
      }
      ApplicationInstall done = uninstalled;
      // Only the call that moves the install to uninstalled suspends, and only when no other
      // install is active for the tenant: replaying an uninstall of a stale id (uninstall A,
      // reinstall as B, uninstall A again) must not suspend the bindings B reactivated.
      boolean suspend = changed && system(() -> applicationInstallRepo.findActiveWithIgnoreRules(
         done.getApplicationId(), done.getRealmRefName(), done.getTenantId())).isEmpty();
      long suspended = suspend ? system(() -> userAppRoleRepo.suspendGroupBindingsForUninstall(
         done.getApplicationId(), done.getRealmRefName(), done.getTenantId(), done.getId())) : 0L;
      Log.infof("application-install.uninstall id=%s application=%s realm=%s tenant=%s groupBindingsSuspended=%d",
         id, done.getApplicationId(), done.getRealmRefName(), done.getTenantId(), suspended);
      return done;
   }

   /** The installs of the caller's realm for tenants the caller administers. */
   public List<ApplicationInstall> listInstalls(String callerRealm) {
      accessDirectory.requireLocalDirectory();
      List<ApplicationInstall> all = system(() -> applicationInstallRepo.findByRealmWithIgnoreRules(callerRealm));
      List<ApplicationInstall> visible = new ArrayList<>();
      for (ApplicationInstall install : all) {
         if (authorityFor(callerRealm, List.of(install.getTenantId())) != Authority.NONE) {
            visible.add(install);
         }
      }
      return visible;
   }

   // ------------------------------------------------------------------ helpers

   private void requireMayGrant(String realm, Application application, UserAppRole binding) {
      List<String> tenants = ApplicationBindingValidator.tenants(binding);
      Authority authority = authorityFor(realm, tenants);
      if (authority == Authority.NONE) {
         throw new ApplicationAccessException(Code.ROLE_NOT_GRANTABLE, String.format(
            "the caller administers none or not all of the tenants %s in realm '%s'", tenants, realm));
      }
      ApplicationBindingValidator.requireGrantable(application, binding.getRoles(), authority,
         userAppRoleRepo.alsoReservedRoles());
   }

   /**
    * {@code defaultRealm=true} chooses the realm a user's requests run in, so only that user or the
    * platform may set it; a stored {@code true} may be kept by any writer. A group binding never
    * chooses a realm.
    */
   private void requireDefaultRealmSetter(UserAppRole request, UserAppRole existing, PrincipalContext caller,
                                          Authority probe) {
      if (!Boolean.TRUE.equals(request.getDefaultRealm())) {
         return;
      }
      if (request.groupBinding()) {
         throw new ApplicationAccessException(Code.BINDING_INVALID, "a group binding never sets defaultRealm");
      }
      boolean kept = existing != null && Boolean.TRUE.equals(existing.getDefaultRealm());
      boolean self = caller.getUserId() != null && caller.getUserId().trim().equalsIgnoreCase(request.getUserId().trim());
      if (!kept && !self && probe != Authority.PLATFORM) {
         throw new ApplicationAccessException(Code.ROLE_NOT_GRANTABLE,
            "only " + request.getUserId().trim() + " or the platform may make this binding's realm the default");
      }
   }

   /**
    * A group binding grants exactly the group's owner tenant (the caller's authority over it is
    * then checked like any binding's). A group with no owner tenant may be bound by the platform only.
    */
   private void requireGroupBindable(UserAppRole request, Authority probe) {
      UserGroup group = system(() -> userGroupRepo.findByRefNameWithIgnoreRules(
            envConfigUtils.getSystemRealm(), request.groupRefName()))
         .orElseThrow(() -> new ApplicationAccessException(Code.BINDING_SUBJECT_INVALID,
            "group '" + request.getGroupRefName() + "' does not exist"));
      String owner = group.getOwnerTenantId() == null || group.getOwnerTenantId().isBlank()
         ? null : group.getOwnerTenantId().trim();
      if (owner == null) {
         if (probe != Authority.PLATFORM) {
            throw new ApplicationAccessException(Code.ROLE_NOT_GRANTABLE,
               "group '" + group.getRefName() + "' has no owner tenant; only the platform may bind it");
         }
         return;
      }
      if (!ApplicationBindingValidator.tenants(request).equals(List.of(owner))) {
         throw new ApplicationAccessException(Code.BINDING_INVALID, String.format(
            "a binding of group '%s' grants exactly its owner tenant '%s'", group.getRefName(), owner));
      }
   }

   private void requireInstallAuthority(String realm, String tenantId) {
      if (tenantId == null || tenantId.isBlank()) {
         throw new ApplicationAccessException(Code.INSTALL_INVALID, "tenantId is required");
      }
      Authority authority = authorityFor(realm, List.of(tenantId.trim()));
      if (authority != Authority.PLATFORM && authority != Authority.REALM_ADMIN) {
         throw new ApplicationAccessException(Code.INSTALL_NOT_AUTHORIZED, String.format(
            "only the platform or a realm administrator of tenant '%s' may install or uninstall in realm '%s'",
            tenantId, realm));
      }
   }

   private static void requireTenant(String tenantId) {
      if (tenantId == null || tenantId.isBlank()) {
         throw new ApplicationAccessException(Code.INSTALL_INVALID, "tenantId is required");
      }
   }

   /**
    * The application an install request may be written for by the caller's own service identity,
    * or {@code null} when the caller is not a service identity (it is then authorized as anyone
    * else). A service identity ({@link PrincipalContext#serviceIdentity()}) acting for nobody else
    * may name only the application of its own token ({@code azp}, the principal's application);
    * any other application, a wildcard, or a token without an application is refused.
    */
   private String ownServiceApplication(String requestedApplicationId) {
      if (!callerIsServiceIdentity()) {
         return null;
      }
      PrincipalContext caller = SecurityContext.getPrincipalContext().orElseThrow();
      String own = callerApplicationId(caller);
      String requested = trimmed(requestedApplicationId);
      if (own == null || own.contains("*") || !own.equals(requested) || delegated(caller)) {
         throw new ApplicationAccessException(Code.INSTALL_NOT_AUTHORIZED, String.format(
            "a service identity installs only its own application; this one is for application '%s' and the install names '%s'",
            own, requested));
      }
      return own;
   }

   private static boolean callerIsServiceIdentity() {
      return SecurityContext.getPrincipalContext().map(PrincipalContext::serviceIdentity).orElse(false);
   }

   /** A request made for another subject (impersonation or acting on behalf) is not the service's own. */
   private static boolean delegated(PrincipalContext caller) {
      return trimmed(caller.getActingOnBehalfOfUserId()) != null || trimmed(caller.getActingOnBehalfOfSubject()) != null
         || trimmed(caller.getImpersonatedByUserId()) != null || trimmed(caller.getImpersonatedBySubject()) != null;
   }

   /** Whether {@code install} is one the caller's service identity may uninstall: its own application, in a declared realm. */
   private boolean ownDeclaredInstall(ApplicationInstall install) {
      PrincipalContext caller = SecurityContext.getPrincipalContext().orElseThrow();
      String own = callerApplicationId(caller);
      if (own == null || own.contains("*") || delegated(caller) || !own.equals(install.getApplicationId())) {
         return false;
      }
      Optional<Application> application = applicationRepo.findByRefNameWithIgnoreRules(own);
      return application.isPresent() && declaredRealms(application.get()).contains(install.getRealmRefName())
         && !install.getRealmRefName().equals(envConfigUtils.getSystemRealm());
   }

   /**
    * {@code requested} when it is a realm {@code application} declares; a service identity names
    * the realm, since its own request runs in the system realm.
    */
   private String declaredRealm(Application application, String requested) {
      String named = trimmed(requested);
      List<String> declared = declaredRealms(application);
      if (named == null || !declared.contains(named) || named.equals(envConfigUtils.getSystemRealm())) {
         throw new ApplicationAccessException(Code.REALM_MISMATCH, String.format(
            "a service identity installs application '%s' only in a realm it declares (tenancy placement %s: %s), not '%s'",
            application.getRefName(), ApplicationTenancy.PLACEMENT_REALMS, declared, named));
      }
      return named;
   }

   /**
    * The concrete realms {@code application} declares: {@code tenancy.realms} under placement
    * {@link ApplicationTenancy#PLACEMENT_REALMS}. Pooled and dedicated placements declare no realm
    * by name, so they have none here.
    */
   static List<String> declaredRealms(Application application) {
      ApplicationTenancy tenancy = application == null ? null : application.getTenancy();
      if (tenancy == null || tenancy.getPlacement() == null
            || !ApplicationTenancy.PLACEMENT_REALMS.equalsIgnoreCase(tenancy.getPlacement().trim())
            || tenancy.getRealms() == null) {
         return List.of();
      }
      return tenancy.getRealms().stream()
         .map(ApplicationAccessService::trimmed)
         .filter(Objects::nonNull)
         .filter(realm -> !realm.contains("*"))
         .toList();
   }

   private Authority authorityFor(String realm, List<String> tenants) {
      if (tenants.isEmpty()) {
         // Only the platform has authority independent of tenants.
         return isPlatform() ? Authority.PLATFORM : Authority.NONE;
      }
      return accessInviteService.administrativeAuthority(realm, tenants);
   }

   /** The platform is the framework's system principal, by the one definition invites use. */
   private boolean isPlatform() {
      return accessInviteService.callerIsSystemPrincipal();
   }

   /** The caller's realm; a different one only for the platform. */
   private static String realmFor(String callerRealm, String requested, Authority authority) {
      String named = requested == null || requested.isBlank() ? null : requested.trim();
      if (named == null || named.equals(callerRealm)) {
         if (callerRealm == null || callerRealm.isBlank()) {
            throw new ApplicationAccessException(Code.REALM_MISMATCH, "the request has no resolved realm");
         }
         return callerRealm;
      }
      if (authority != Authority.PLATFORM) {
         throw new ApplicationAccessException(Code.REALM_MISMATCH,
            "realm '" + named + "' is not the caller's realm '" + callerRealm + "'");
      }
      return named;
   }

   private Application application(String applicationId) {
      if (applicationId == null || applicationId.isBlank()) {
         throw new ApplicationAccessException(Code.BINDING_INVALID, "applicationId is required");
      }
      return applicationRepo.findByRefNameWithIgnoreRules(applicationId.trim())
         .orElseThrow(() -> new ApplicationAccessException(Code.APPLICATION_UNKNOWN,
            "application '" + applicationId + "' is not registered"));
   }

   private Optional<UserAppRole> stored(UserAppRole request) {
      if (request.getId() != null) {
         Optional<UserAppRole> byId = userAppRoleRepo.findByIdWithIgnoreRules(request.getId());
         if (byId.isPresent()) {
            return byId;
         }
      }
      String user = request.getUserId() == null || request.getUserId().isBlank() ? null : request.getUserId().trim();
      String group = request.groupRefName();
      if ((user == null) == (group == null) || request.getApplicationId() == null) {
         return Optional.empty();
      }
      return userAppRoleRepo.findForSubjectsWithIgnoreRules(user, group == null ? List.of() : List.of(group),
            request.getApplicationId().trim(), request.getRealmRefName()).stream()
         .filter(binding -> group == null ? user.equals(binding.getUserId()) : group.equals(binding.getGroupRefName()))
         .findFirst();
   }

   /**
    * Who suspended the binding. An uninstall's marker survives a rewrite that keeps the binding
    * suspended, so the reinstall still reactivates it; otherwise a suspension is the writer's.
    */
   private static String suspendedBy(UserAppRole request, UserAppRole existing, PrincipalContext caller) {
      if (!UserAppRole.STATUS_SUSPENDED.equals(request.getStatus())) {
         return null;
      }
      if (existing != null && UserAppRole.STATUS_SUSPENDED.equals(existing.getStatus())
            && existing.getSuspendedBy() != null
            && existing.getSuspendedBy().startsWith(UserAppRole.SUSPENDED_BY_UNINSTALL)) {
         return existing.getSuspendedBy();
      }
      return "user:" + caller.getUserId();
   }

   private static String trimmed(String value) {
      return value == null || value.isBlank() ? null : value.trim();
   }

   private String tenantOf(String tenantId) {
      if (tenantId != null && !tenantId.isBlank()) {
         return tenantId.trim();
      }
      PrincipalContext caller = SecurityContext.getPrincipalContext().orElse(null);
      String tenant = caller == null || caller.getDataDomain() == null ? null : caller.getDataDomain().getTenantId();
      if (tenant == null || tenant.isBlank()) {
         throw new ApplicationAccessException(Code.BINDING_INVALID, "a tenant is required");
      }
      return tenant.trim();
   }

   private static String subject(UserAppRole binding) {
      return binding.groupBinding() ? "group:" + binding.getGroupRefName() : binding.getUserId();
   }

   /**
    * Runs repository work as the system principal once this service has decided the caller's
    * authority. The raised scope keeps the request's application, so deployments that require an
    * application on every policy evaluation ({@code quantum.security.policy-scope.require-application})
    * accept the write. It marks the caller as acting-on-behalf-of on the principal; audit records keep
    * the actor in this service's own fields ({@code grantedBy}, {@code installedBy}, ...).
    */
   private <T> T system(Supplier<T> action) {
      PrincipalContext caller = SecurityContext.getPrincipalContext().orElse(null);
      String applicationId = callerApplicationId(caller);
      PrincipalContext system = securityUtils.getSystemPrincipalContext();
      ResourceContext systemResource = securityUtils.getSystemSecurityResourceContext();
      if (applicationId == null) {
         if (requireApplicationScope) {
            throw new ApplicationAccessException(Code.APPLICATION_SCOPE_REQUIRED,
               "the request names no application, and this deployment requires one");
         }
         return SecurityCallScope.runWithContexts(system, systemResource, action);
      }
      PrincipalContext.Builder principal = new PrincipalContext.Builder()
         .withDefaultRealm(system.getDefaultRealm())
         .withApplicationId(applicationId)
         .withDomainContext(system.getDomainContext())
         .withDataDomain(system.getDataDomain())
         .withUserId(system.getUserId())
         .withRoles(system.getRoles())
         .withScope(system.getScope());
      if (caller != null && caller.getUserId() != null && !caller.getUserId().equals(system.getUserId())) {
         principal.withActingOnBehalfOfUserId(caller.getUserId());
      }
      ResourceContext resource = new ResourceContext.Builder()
         .withRealm(systemResource.getRealm())
         .withApplicationId(applicationId)
         .withArea(systemResource.getArea())
         .withFunctionalDomain(systemResource.getFunctionalDomain())
         .withAction(systemResource.getAction())
         .build();
      return SecurityCallScope.runWithContexts(principal.build(), resource, action);
   }

   /**
    * The application of the request, resolved as {@code RuleContext} does: the resource's, else the
    * principal's; the two must agree when both are set.
    */
   private static String callerApplicationId(PrincipalContext caller) {
      String fromPrincipal = caller == null ? null : trimmed(caller.getApplicationId());
      String fromResource = SecurityContext.getResourceContext()
         .map(resource -> trimmed(resource.getApplicationId()))
         .orElse(null);
      if (fromPrincipal != null && fromResource != null && !fromPrincipal.equals(fromResource)) {
         throw new ApplicationAccessException(Code.APPLICATION_SCOPE_MISMATCH,
            "the request's principal names application '" + fromPrincipal
               + "' and its resource names '" + fromResource + "'");
      }
      return fromResource != null ? fromResource : fromPrincipal;
   }
}
