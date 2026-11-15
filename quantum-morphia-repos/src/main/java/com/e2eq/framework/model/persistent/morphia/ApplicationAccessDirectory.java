package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.Application;
import com.e2eq.framework.model.security.ApplicationAccessException;
import com.e2eq.framework.model.security.ApplicationAccessException.Code;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.UserAppRole;
import com.e2eq.framework.model.security.UserGroup;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.framework.util.EnvConfigUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What principal resolution reads about an identity's access to an application: its bindings
 * ({@link UserAppRole}) and those of its groups, the registered {@link Application}, and the
 * application's installs. Everything is in the system realm.
 *
 * <p>Under a remote directory ({@code quantum.directory.mode=remote}, or {@code quantum.mode=remote}
 * when the directory mode is unset, as {@code QuantumModeConfig#membershipsRemote}) this service
 * is not the directory owner: no local binding or install is an authority. Bindings are then not
 * readable here ({@link #bindingsReadable()}), {@link #bindingsFor} refuses, and writes refuse
 * ({@link #requireLocalDirectory()}) with {@code DIRECTORY_REMOTE}. Nothing here reads the remote
 * directory's bindings, so whoever resolves principals for an application must own the directory;
 * principal resolution refuses rather than fall back to the realm assignment when it does not.
 */
@ApplicationScoped
public class ApplicationAccessDirectory {

   @Inject
   UserAppRoleRepo userAppRoleRepo;

   @Inject
   ApplicationInstallRepo applicationInstallRepo;

   @Inject
   ApplicationRepo applicationRepo;

   @Inject
   UserProfileRepo userProfileRepo;

   @Inject
   UserGroupRepo userGroupRepo;

   @Inject
   EnvConfigUtils envConfigUtils;

   @ConfigProperty(name = "quantum.directory.mode")
   Optional<String> directoryMode = Optional.empty();

   @ConfigProperty(name = "quantum.mode")
   Optional<String> quantumMode = Optional.empty();

   /**
    * Whether this service owns the binding store: false under {@code quantum.directory.mode=remote}
    * or, when that is unset, {@code quantum.mode=remote}.
    */
   public boolean bindingsReadable() {
      Optional<String> explicit = directoryMode == null ? Optional.empty()
         : directoryMode.map(String::trim).filter(value -> !value.isEmpty());
      if (explicit.isPresent()) {
         return !"remote".equalsIgnoreCase(explicit.get());
      }
      return quantumMode == null || quantumMode.map(String::trim).filter("remote"::equalsIgnoreCase).isEmpty();
   }

   /** Refuses with {@code DIRECTORY_REMOTE} unless this service owns the binding store. */
   public void requireLocalDirectory() {
      if (!bindingsReadable()) {
         throw new ApplicationAccessException(Code.DIRECTORY_REMOTE,
            "application bindings and installs are held by the remote directory; write them there");
      }
   }

   /**
    * The bindings of {@code credential} and of the system-realm groups it belongs to, for
    * {@code applicationId}, in {@code realmRefName} or, when null, in every realm. Whatever their
    * status or shape: resolution validates each one and fails closed. Refuses with
    * {@code DIRECTORY_REMOTE} when the bindings are held by the remote directory: an empty answer
    * would read as "no binding" and let the realm assignment decide.
    */
   public List<UserAppRole> bindingsFor(CredentialUserIdPassword credential, String applicationId, String realmRefName) {
      requireLocalDirectory();
      if (credential == null) {
         return List.of();
      }
      return userAppRoleRepo.findForSubjectsWithIgnoreRules(
         credential.getUserId(), groupRefNames(credential), applicationId, realmRefName);
   }

   public Optional<Application> application(String refName) {
      return applicationRepo.findByRefNameWithIgnoreRules(refName);
   }

   public boolean installed(String applicationId, String realmRefName, String tenantId) {
      return applicationInstallRepo.isInstalled(applicationId, realmRefName, tenantId);
   }

   /** The owner tenant of a system-realm group, or null when the group has none or does not exist. */
   public String groupOwnerTenant(String groupRefName) {
      if (groupRefName == null || groupRefName.isBlank()) {
         return null;
      }
      return userGroupRepo.findByRefNameWithIgnoreRules(envConfigUtils.getSystemRealm(), groupRefName.trim())
         .map(UserGroup::getOwnerTenantId)
         .map(String::trim)
         .filter(owner -> !owner.isEmpty())
         .orElse(null);
   }

   /** RefNames of the system-realm groups the credential's profile belongs to. */
   List<String> groupRefNames(CredentialUserIdPassword credential) {
      if (credential.getSubject() == null) {
         return List.of();
      }
      String systemRealm = envConfigUtils.getSystemRealm();
      Optional<UserProfile> profile = userProfileRepo.getBySubject(systemRealm, credential.getSubject());
      if (profile.isEmpty()) {
         return List.of();
      }
      List<UserGroup> groups = userGroupRepo.findByUserProfileRefWithIgnoreRules(
         systemRealm, profile.get().createEntityReference());
      return groups == null ? List.of() : groups.stream()
         .filter(Objects::nonNull)
         .map(UserGroup::getRefName)
         .filter(Objects::nonNull)
         .distinct()
         .toList();
   }
}
