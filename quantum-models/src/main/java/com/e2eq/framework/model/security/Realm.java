package com.e2eq.framework.model.security;

import com.e2eq.framework.model.persistent.base.BaseModel;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.IndexOptions;
import dev.morphia.annotations.Indexed;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Entity
@RegisterForReflection
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Realm extends BaseModel {

   /**
    this is the last part of a given email which is being used as an identifier.  So
    mingardia@end2endlogic.com for example the email domain would be "end2endlogic.com".
    */
   @Indexed(options= @IndexOptions(unique=true))
   @NotNull
   @NonNull
   protected String emailDomain;

   /**
    This is the connection string to use to connect to the database server
    */
   protected String connectionString;

   /**
    The database to use with-in that database server.
    */
   @Indexed(options= @IndexOptions(unique=true))
   @NotNull
   @NonNull
   protected String databaseName;

   /** the user id of the owner of this realm */
   protected String defaultAdminUserId;


   public String getDefaultAdminUserId() {
      if (defaultAdminUserId == null) {
         return "admin@" + emailDomain;
      } else {
         return defaultAdminUserId;
      }
   }
   /**
    * The domain context to use by default for uses that authenticate with in this realm.
    */
   @Valid
   @NotNull
   @NonNull
   protected DomainContext domainContext;

   @Override
   public String bmFunctionalArea() {
      return "SECURITY";
   }

   @Override
   public String bmFunctionalDomain() {
      return "REALM";
   }


}
