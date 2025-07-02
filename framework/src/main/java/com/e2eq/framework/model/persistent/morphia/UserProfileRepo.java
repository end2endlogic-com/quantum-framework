package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.persistent.security.CredentialUserIdPassword;
import com.e2eq.framework.model.persistent.security.DomainContext;
import com.e2eq.framework.model.persistent.security.UserProfile;
import com.e2eq.framework.model.security.auth.AuthProviderFactory;
import com.e2eq.framework.model.security.auth.UserManagement;
import com.e2eq.framework.util.EncryptionUtils;
import com.e2eq.framework.util.SecurityUtils;
import com.e2eq.framework.util.ValidateUtils;
import com.mongodb.client.model.ReturnDocument;
import dev.morphia.Datastore;
import dev.morphia.ModifyOptions;
import dev.morphia.query.Query;
import dev.morphia.query.filters.Filters;
import dev.morphia.query.updates.UpdateOperators;
import dev.morphia.transactions.MorphiaSession;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.ValidationException;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.NonNull;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.*;

@ApplicationScoped
public class UserProfileRepo extends MorphiaRepo<UserProfile> {

   @Inject
   CredentialRepo credRepo;

   @Inject
   SecurityUtils securityUtils;

   @Inject
   AuthProviderFactory authProviderFactory;
   @Inject
   CredentialRepo credentialRepo;

   public Optional<UserProfile> updateStatus( @NotNull String userId, @NotNull UserProfile.Status status) {
      return updateStatus(getSecurityContextRealmId(), userId, status);
   }

   public Optional<UserProfile> updateStatus(String realm, @NotNull String userId, @NotNull UserProfile.Status status) {
     UserProfile p = this.morphiaDataStore.getDataStore(realm)
             .find(UserProfile.class)
             .filter(Filters.eq("userId", userId)).modify(new ModifyOptions().returnDocument(ReturnDocument.AFTER), UpdateOperators.set("status", status.value()));

     return Optional.ofNullable(p);
   }

   public Optional<UserProfile> getByUsername(@NotNull String username) {
     return getByUsername(getSecurityContextRealmId(), username);
   }

   public Optional<UserProfile> getByUsername(String realm, @NotNull String username) {
      return getByUsername(  morphiaDataStore.getDataStore(realm), username );
   }

   public Optional<UserProfile> getByUsername(Datastore datastore,@NotNull String username) {
      Query<UserProfile> q = datastore.find(this.getPersistentClass()).filter(
         Filters.and(
            Filters.eq("username", username)
         )
      );

      UserProfile p = q.first();

      return Optional.ofNullable(p);
   }


   public Optional<UserProfile> getByUserId(@NotNull String userId) {
      return getByUserId(  morphiaDataStore.getDataStore(getSecurityContextRealmId()), userId );
   }

   public Optional<UserProfile> getByUserId(@NotNull String realm, @NotNull String userId) {
      return getByUserId(  morphiaDataStore.getDataStore(realm), userId );
   }

   public Optional<UserProfile> getByUserId(Datastore datastore,@NotNull String userId) {
      Query<UserProfile> q = datastore.find(this.getPersistentClass()).filter(
         Filters.and(
            Filters.eq("userId", userId)
            )
      );

      UserProfile p = q.first();

      return Optional.ofNullable(p);
   }

   public UserProfile createUser( @NotNull String realmId,
                                  @Valid UserProfile up,
                                  @NotNull @NotEmpty String[] roles,
                                  @NotNull @NonNull @NotEmpty @Size(min=8, max=50, message = "password must be between 8 and 50 characters") String password) {
      return createUser(morphiaDataStore.getDataStore(realmId), up, roles, password);
   }

   public UserProfile createUser( @Valid UserProfile up,
                                 @NotNull @NotEmpty String[] roles,
                                 @NotNull @NonNull @NotEmpty @Size(min=8, max=50, message = "password must be between 8 and 50 characters") String password) {
      return createUser(morphiaDataStore.getDataStore(getSecurityContextRealmId()), up, roles, password);
   }

   public UserProfile createUser(Datastore  datastore,
                                 @Valid UserProfile up,
                                 @NotNull @NotEmpty String[] roles,
                                 @NotNull @NonNull @NotEmpty @Size(min=8, max=50, message = "password must be between 8 and 50 characters") String password) {
      return createUser(datastore, up, roles, password, securityUtils.getDefaultDomainContext());
   }

   public UserProfile createUser(Datastore  datastore,
                                 @Valid UserProfile up,
                                 @NotNull @NotEmpty String[] roles,
                                 @NotNull @NonNull @NotEmpty @Size(min=8, max=50, message = "password must be between 8 and 50 characters") String password,
                                 DomainContext domainContext) {

      if (!ValidateUtils.isValidEmailAddress(up.getUserId())) {
         throw new ValidationException("userId:" + up.getUserId() + " must be an email address");
      }


      // convert roles array to a set of strings
      Set<String> roleSet = new HashSet<>(Arrays.asList(roles));

      // check if the user exists in the authProvider:
      // if not, create the user in the authProvider
      // if the user exists, update the roles in the auth provider
      UserManagement userManager = authProviderFactory.getUserManager();
      if (!userManager.usernameExists(datastore.getDatabase().getName(), up.getUsername())) {
         // create the user in the system using the provided auth provider
         // this will handle the password encryption and storage in the system
         // and will also handle the creation of the user in the authentication system
         // (like LDAP, Active Directory, etc.)
         authProviderFactory.getUserManager().createUser(datastore.getDatabase().getName(), up.getUserId(), password, up.getUsername(), roleSet, domainContext);
      } else {
        Log.warnf("User  with username %s already exists in the auth provider. skipping create", up.getUsername());
      }

      Optional<CredentialUserIdPassword> ocred = credentialRepo.findByUserId( up.getUserId(), datastore.getDatabase().getName());
      if (ocred.isPresent()) {
         up.setUsername(ocred.get().getUsername());
         up.setRefName(ocred.get().getRefName());
         Log.infof("updated userprofile with username %s", up.getUsername());
      } else {
         throw new IllegalStateException(String.format("Failed to find the user in the credential repository for userId %s in realm:%s", up.getUserId(), datastore.getDatabase().getName()));
      }

      // now create the user profile
      up = save(datastore,up);

      return up;
   }

   @Override
   public long delete (String realmId, UserProfile obj) throws ReferentialIntegrityViolationException {
      long ret = 0;
      try (MorphiaSession s = morphiaDataStore.getDataStore(realmId).startSession()) {
         s.startTransaction();
         Optional<CredentialUserIdPassword> ocred = credentialRepo.findByUserId( obj.getUserId(), realmId);
         if (ocred.isPresent()) {
            credentialRepo.delete(s, ocred.get());
         }
         ret= super.delete(s, obj);
         s.commitTransaction();
      }
      return ret;
   }
}
