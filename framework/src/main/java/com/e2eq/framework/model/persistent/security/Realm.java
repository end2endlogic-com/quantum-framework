package com.e2eq.framework.model.persistent.security;

import com.e2eq.framework.model.persistent.base.BaseModel;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.IndexOptions;
import dev.morphia.annotations.Indexed;
import io.quarkus.runtime.annotations.RegisterForReflection;

@Entity
@RegisterForReflection
public class Realm extends BaseModel {

   /**
    this is the last part of a given email which is being used as an identifier.  So
    mingardia@end2endlogic.com for example the email domain would be "end2endlogic.com".
    */
   @Indexed(options= @IndexOptions(unique=true))
   protected String emailDomain;

   /**
    This is the connection string to use to connect to the database server
    */
   protected String connectionString;

   /**
    The database to use with-in that database server.
    */
   protected String databaseName;

   protected String tenantId;
   protected String orgRefName;
   protected String accountNumber;
   protected String defaultAdminUserId;


   public String getEmailDomain () {
      return emailDomain;
   }

   public void setEmailDomain (String emailDomain) {
      this.emailDomain = emailDomain;
   }

   public String getConnectionString () {
      return connectionString;
   }

   public void setConnectionString (String connectionString) {
      this.connectionString = connectionString;
   }

   public String getDatabaseName () {
      return databaseName;
   }

   public void setDatabaseName (String databaseName) {
      this.databaseName = databaseName;
   }

   @Override
   public String bmFunctionalArea() {
      return "SECURITY";
   }

   @Override
   public String bmFunctionalDomain() {
      return "REALM";
   }

   public String getTenantId () {
      return tenantId;
   }

   public void setTenantId (String tenantId) {
      this.tenantId = tenantId;
   }

   public String getOrgRefName () {
      return orgRefName;
   }

   public void setOrgRefName (String orgRefName) {
      this.orgRefName = orgRefName;
   }

   public String getAccountNumber () {
      return accountNumber;
   }

   public void setAccountNumber (String accountNumber) {
      this.accountNumber = accountNumber;
   }

   public String getDefaultAdminUserId () {
      return defaultAdminUserId;
   }

   public void setDefaultAdminUserId (String defaultAdminUserId) {
      this.defaultAdminUserId = defaultAdminUserId;
   }

   @Override
   public boolean equals (Object o) {
      if (this == o) return true;
      if (!(o instanceof Realm)) return false;
      if (!super.equals(o)) return false;

      Realm realm = (Realm) o;

      if (emailDomain != null ? !emailDomain.equals(realm.emailDomain) : realm.emailDomain != null) return false;
      if (connectionString != null ? !connectionString.equals(realm.connectionString) : realm.connectionString != null)
         return false;
      if (databaseName != null ? !databaseName.equals(realm.databaseName) : realm.databaseName != null) return false;
      if (tenantId != null ? !tenantId.equals(realm.tenantId) : realm.tenantId != null) return false;
      if (orgRefName != null ? !orgRefName.equals(realm.orgRefName) : realm.orgRefName != null) return false;
      if (accountNumber != null ? !accountNumber.equals(realm.accountNumber) : realm.accountNumber != null)
         return false;
      return defaultAdminUserId != null ? defaultAdminUserId.equals(realm.defaultAdminUserId) :
                realm.defaultAdminUserId == null;
   }

   @Override
   public int hashCode () {
      int result = super.hashCode();
      result = 31 * result + (emailDomain != null ? emailDomain.hashCode() : 0);
      result = 31 * result + (connectionString != null ? connectionString.hashCode() : 0);
      result = 31 * result + (databaseName != null ? databaseName.hashCode() : 0);
      result = 31 * result + (tenantId != null ? tenantId.hashCode() : 0);
      result = 31 * result + (orgRefName != null ? orgRefName.hashCode() : 0);
      result = 31 * result + (accountNumber != null ? accountNumber.hashCode() : 0);
      result = 31 * result + (defaultAdminUserId != null ? defaultAdminUserId.hashCode() : 0);
      return result;
   }
}
