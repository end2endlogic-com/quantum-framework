package com.e2eq.framework.model.security;


import java.util.Date;
import java.util.List;
import java.util.Map;

import com.e2eq.framework.util.EncryptionUtils;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.mongodb.client.model.CollationStrength;
import dev.morphia.annotations.*;
import io.quarkus.runtime.annotations.RegisterForReflection;
import com.e2eq.framework.model.persistent.base.BaseModel;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;
import lombok.experimental.SuperBuilder;


@Entity()
@Data
@EqualsAndHashCode(callSuper = true)
@RegisterForReflection
@SuperBuilder
@NoArgsConstructor
public class CredentialUserIdPassword extends BaseModel {

   @Data
   @EqualsAndHashCode
   @ToString
   public class RealmEntry {
      String realmRefName;
      String realmDisplayName;
   }

   @Data
   @EqualsAndHashCode
   @ToString
   public class ImpersonateEntry {
      String userId;
      String subject;
      String userDisplayName;
   }

   @Indexed(options = @IndexOptions(unique = true, collation = @Collation(locale = "en", strength =
                                                                                            CollationStrength.SECONDARY)))
   @NotNull(message = "userId must be provided for userIdPassword credential")
   @NonNull
   @Size(min = 3, max = 50, message = "userId length must be less than or equal to 50 and greater than or equal to 3 " +
                                         "characters")
   protected String userId;

   @Indexed(options = @IndexOptions(unique = true, collation = @Collation(locale = "en", strength =
                                                                                            CollationStrength.SECONDARY)))
   @NonNull
   @NotNull(message = "subject must be non null")
   @NotEmpty
   protected String subject;

   /** optional field to provide a description of the credential */
   protected String description;

   /** optional field to provide an email of the responsible party */
   protected String emailOfResponsibleParty;

   @NonNull
   @NotNull(message = "domain context is required")
   @Valid
   protected DomainContext domainContext;

   // Optional data domain policy attached to this credential (overrides global)
   protected DataDomainPolicy dataDomainPolicy;

   // ignored for rest api's for security reasons we don't expose this field to the rest api.
   @JsonIgnore
   @ToString.Exclude
   protected String passwordHash;
   Boolean forceChangePassword;
   protected String[] roles; // can be set by identity provider or explictly here, code should union token roles and
   // this.

   protected String issuer;

   @ToString.Exclude
   @Builder.Default
   @NonNull
   @JsonIgnore
   protected String hashingAlgorithm = EncryptionUtils.hashAlgorithm();

   @NonNull
   protected Date lastUpdate;

   @ToString.Exclude
   protected Map<String, String> area2RealmOverrides;

   protected Boolean superUser;

   // this is really a script.
   @ToString.Exclude
   protected String impersonateFilterScript;
   @ToString.Exclude
   protected List<String> impersonateFilterScriptParams;

   protected List<ImpersonateEntry> authorizedImpersonateIds;

   protected List<RealmEntry> authorizedRealms;
   //@Regex
   @ToString.Exclude
   protected String realmRegEx; // if the authorizedRealms is not found or null, then this is used if defined
   protected String authProviderName; // the provider this record belongs to.


   @Override
   public String bmFunctionalArea () {
      return "SECURITY";
   }

   @Override
   public String bmFunctionalDomain () {
      return "CREDENTIAL_USERID_PASSWORD";
   }

}
