package com.e2eq.framework.security.model.persistent.morphia;

import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
import com.e2eq.framework.security.model.persistent.models.security.CredentialUserIdPassword;
import com.e2eq.framework.security.model.persistent.models.security.UserProfile;
import com.e2eq.framework.util.EncryptionUtils;
import com.e2eq.framework.util.ValidateUtils;
import com.mongodb.client.model.ReturnDocument;
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

import java.util.Date;
import java.util.Optional;

@ApplicationScoped
public class UserProfileRepo extends MorphiaRepo<UserProfile> {

   @Inject
   CredentialRepo credRepo;


   public Optional<UserProfile> updateStatus(@NotNull String userId, @NotNull UserProfile.Status status) {
     UserProfile p = this.dataStore.getDataStore(getDefaultRealmId())
             .find(UserProfile.class)
             .filter(Filters.eq("userId", userId)).modify(new ModifyOptions().returnDocument(ReturnDocument.AFTER), UpdateOperators.set("status", status.value()));

     return Optional.ofNullable(p);
   }

   public Optional<UserProfile> getByUserId(@NotNull String userId) {
      Query<UserProfile> q = this.dataStore.getDataStore(getDefaultRealmId()).find(this.getPersistentClass()).filter(
         Filters.and(
            Filters.eq("userId", userId)
            )
      );

      UserProfile p = q.first();

      return Optional.ofNullable(p);
   }

   public UserProfile createUser(@NonNull String realm,
                                 @Valid UserProfile up,
                                 @NotNull @NotEmpty String[] roles,
                                 @NotNull @NonNull @NotEmpty @Size(min=4, max=50) String password) {

      if (!ValidateUtils.isValidEmailAddress(up.getUserId())) {
         throw new ValidationException("userId:" + up.getUserId() + " must be an email address");
      }

      up = save(up);

      CredentialUserIdPassword cred = new CredentialUserIdPassword();
      cred.setDefaultRealm(realm);
      cred.setUserId(up.getUserId());
      cred.setRefName(up.getUserId());
      cred.setRoles(roles);

      cred.setDataDomain(up.getDataDomain());
      cred.setLastUpdate(new Date());
      cred.setPasswordHash(EncryptionUtils.hashPassword(password));
      credRepo.save(cred);

      return up;
   }
}
