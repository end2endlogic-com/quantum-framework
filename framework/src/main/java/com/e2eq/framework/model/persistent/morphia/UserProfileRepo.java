package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.security.CredentialUserIdPassword;
import com.e2eq.framework.model.persistent.security.DomainContext;
import com.e2eq.framework.model.persistent.security.UserProfile;
import com.e2eq.framework.util.EncryptionUtils;
import com.e2eq.framework.util.ValidateUtils;
import com.mongodb.client.model.ReturnDocument;
import dev.morphia.Datastore;
import dev.morphia.ModifyOptions;
import dev.morphia.query.Query;
import dev.morphia.query.filters.Filters;
import dev.morphia.query.updates.UpdateOperators;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.ValidationException;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.NonNull;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Date;
import java.util.Optional;

@ApplicationScoped
public class UserProfileRepo extends MorphiaRepo<UserProfile> {

   @Inject
   CredentialRepo credRepo;

   @ConfigProperty(name="auth.provider")
   private String authProvider;


   public Optional<UserProfile> updateStatus(@NotNull String userId, @NotNull UserProfile.Status status) {
     UserProfile p = this.morphiaDataStore.getDataStore(getSecurityContextRealmId())
             .find(UserProfile.class)
             .filter(Filters.eq("userId", userId)).modify(new ModifyOptions().returnDocument(ReturnDocument.AFTER), UpdateOperators.set("status", status.value()));

     return Optional.ofNullable(p);
   }

   public Optional<UserProfile> getByUserId(@NotNull String userId) {
      return getByUserId(  morphiaDataStore.getDataStore(getSecurityContextRealmId()), userId );
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

   public UserProfile createUser( @Valid UserProfile up,
                                 @NotNull @NotEmpty String[] roles,
                                 @NotNull @NonNull @NotEmpty @Size(min=8, max=50, message = "password must be between 8 and 50 characters") String password) {
      return createUser(morphiaDataStore.getDataStore(getSecurityContextRealmId()), up, roles, password);
   }

   public UserProfile createUser(Datastore  datastore,
                                 @Valid UserProfile up,
                                 @NotNull @NotEmpty String[] roles,
                                 @NotNull @NonNull @NotEmpty @Size(min=8, max=50, message = "password must be between 8 and 50 characters") String password) {

      if (!ValidateUtils.isValidEmailAddress(up.getUserId())) {
         throw new ValidationException("userId:" + up.getUserId() + " must be an email address");
      }

      up = save(datastore,up);

      CredentialUserIdPassword cred = new CredentialUserIdPassword();
      DomainContext ctx = new DomainContext(up.getDataDomain(), authProvider);
      cred.setDomainContext(ctx);
      cred.setUserId(up.getUserId());
      cred.setRefName(up.getUserId());
      cred.setRoles(roles);

      cred.setDataDomain(up.getDataDomain());
      cred.setLastUpdate(new Date());
      cred.setPasswordHash(EncryptionUtils.hashPassword(password));
      credRepo.save(datastore, cred);

      return up;
   }
}
