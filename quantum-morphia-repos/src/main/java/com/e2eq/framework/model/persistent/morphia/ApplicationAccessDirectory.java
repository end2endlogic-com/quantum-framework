package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.Application;
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
 * <p>Under {@code quantum.directory.mode=remote} this service is not the directory owner, exactly
 * as for {@link com.e2eq.framework.model.security.UserRealmRole} assignments in {@link IdentityRoleResolver}: no local binding is an
 * authority and none is returned.
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

   boolean localDirectoryAuthoritative() {
      return directoryMode == null || directoryMode
         .map(value -> !"remote".equalsIgnoreCase(value.trim()))
         .orElse(true);
   }

   /**
    * The bindings of {@code credential} and of the system-realm groups it belongs to, for
    * {@code applicationId}, in {@code realmRefName} or, when null, in every realm. Whatever their
    * status or shape: resolution validates each one and fails closed.
    */
   public List<UserAppRole> bindingsFor(CredentialUserIdPassword credential, String applicationId, String realmRefName) {
      if (credential == null || !localDirectoryAuthoritative()) {
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
