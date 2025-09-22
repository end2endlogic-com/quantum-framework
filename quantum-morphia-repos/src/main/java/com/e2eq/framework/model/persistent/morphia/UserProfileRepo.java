package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.auth.UserManagement;

import com.e2eq.framework.util.SecurityUtils;
import com.e2eq.framework.util.ValidateUtils;
import com.mongodb.client.model.ReturnDocument;
import dev.morphia.Datastore;
import dev.morphia.ModifyOptions;
import dev.morphia.query.Query;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import dev.morphia.query.updates.UpdateOperators;
import dev.morphia.transactions.MorphiaSession;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.ValidationException;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.NonNull;

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

   public Optional<UserProfile> getBySubject(@NotNull String subject) {
     return getBySubject(getSecurityContextRealmId(), subject);
   }

   public Optional<UserProfile> getBySubject(String realm, @NotNull String subject) {
      return getBySubject(  morphiaDataStore.getDataStore(realm), subject);
   }

   public Optional<UserProfile> getBySubject(Datastore datastore,@NotNull String subject) {
      Query<UserProfile> q = datastore.find(this.getPersistentClass()).filter(
         Filters.and(
            Filters.eq("credentialUserIdPasswordRef.entityRefName", subject)
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
      // find the credentail for the user
      Optional<CredentialUserIdPassword> ocred = credRepo.findByUserId(userId);

      if (!ocred.isPresent()) {
         Log.warnf("No credential found for user: %s", userId);
         return Optional.empty();
      }
      String credRefName = ocred.get().getRefName();

      List<Filter> filters = new ArrayList<>();
      filters.add(Filters.and(
         Filters.eq("credentialUserIdPasswordRef.entityRefName", credRefName)
         ));

      // build filters calling getFilterArray then add the filter: Filters.eq("credentialUserIdPasswordRef.entityRefName", credRefName)
      Filter[] filtersArray = getFilterArray(filters, getPersistentClass());
      // find the userprofile by the credentialUserIdPasswordReference.entityRefName passing in the credRefName
      Query<UserProfile> q = datastore.find(this.getPersistentClass()).filter(
         filtersArray
      );
      UserProfile p = q.first();
      return Optional.ofNullable(p);
   }

   public UserProfile createUser(
                                  String userId,
                                  String fname,
                                  String lname,
                                  Boolean forceChangePassword,
                                  @NotNull @NotEmpty String[] roles,
                                  @NotNull @NonNull @NotEmpty @Size(min=8, max=50, message = "password must be between 8 and 50 characters") String password) {
      return createUser( userId, fname, lname, forceChangePassword, roles, password, securityUtils.getDefaultDomainContext());
   }


   public UserProfile createUser(
                                 String userId,
                                 String fname,
                                 String lname,
                                 Boolean forceChangePassword,
                                 @NotNull @NotEmpty String[] roles,
                                 @NotNull @NonNull @NotEmpty @Size(min=8, max=50, message = "password must be between 8 and 50 characters") String password,
                                 DomainContext domainContext) {

      // convert roles array to a set of strings
      Set<String> roleSet = new HashSet<>(Arrays.asList(roles));

      // check if the user exists in the authProvider:
      // if not, create the user in the authProvider
      // if the user exists, update the roles in the auth provider
      UserManagement userManager = authProviderFactory.getUserManager();
      if (!userManager.userIdExists( userId)) {
         // create the user in the system using the provided auth provider
         // this will handle the password encryption and storage in the system
         // and will also handle the creation of the user in the authentication system
         // (like LDAP, Active Directory, etc.)
         authProviderFactory.getUserManager().createUser( userId, password, forceChangePassword, roleSet, domainContext);
      } else {
        Log.warnf("User  with userId %s already exists in the auth provider. skipping create", userId);
      }

      UserProfile up;
      Optional<UserProfile> oup = getByUserId( userId);
      if (!oup.isPresent()) {
         up = new UserProfile();
         up.setEmail(userId);
         up.setFname(fname);
         up.setLname(lname);

        // check if credential is present
         Optional<CredentialUserIdPassword> ocred = credentialRepo.findByUserId( userId, envConfigUtils.getSystemRealm(), true);
         if (ocred.isPresent()) {
            // try to find the up
            up.setCredentialUserIdPasswordRef(ocred.get().createEntityReference(envConfigUtils.getSystemRealm()));
         } else {
            throw new IllegalStateException(String.format("Failed to find the user in the credential repository for userId %s", userId));
         }
         // now create the user profile
         up = save(up);
      } else {
         up = oup.get();
         Optional<CredentialUserIdPassword> ocred = credentialRepo.findByUserId( userId, envConfigUtils.getSystemRealm(), true);
         if (ocred.isPresent()) {
            EntityReference ref = ocred.get().createEntityReference(envConfigUtils.getSystemRealm());
            // ensure the reference is correct
            if (!ref.equals(up.getCredentialUserIdPasswordRef())) {
               up.setCredentialUserIdPasswordRef(ref);
               up = save(up);
            }
         } else {
            throw new IllegalStateException(String.format("Failed to find the user in the credential repository for userId %s", userId));
         }
      }

      return up;
   }

   @Override
   public long delete (String realmId, UserProfile obj) throws ReferentialIntegrityViolationException {
      long ret = 0;

      Optional<CredentialUserIdPassword> ocred = credentialRepo.findByRefName( obj.getCredentialUserIdPasswordRef().getEntityRefName(), (obj.getCredentialUserIdPasswordRef().getRealm() != null) ? obj.getCredentialUserIdPasswordRef().getRealm() : envConfigUtils.getSystemRealm());
      if (ocred.isPresent()) {
         credentialRepo.delete(ocred.get());
      }
      ret= super.delete(realmId, obj);

      return ret;
   }
}
