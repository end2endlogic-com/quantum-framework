package com.e2eq.framework.model.persistent.migration.base;

import com.e2eq.framework.model.persistent.base.BaseModel;
import dev.morphia.annotations.*;
import io.quarkus.runtime.annotations.RegisterForReflection;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

@Indexes({
      @Index(
         fields= {@Field("changeSetName"), @Field("changeSetVersion")},
         options=@IndexOptions(unique=true, name="unique-changeset-name-version")
      )
})
@Entity
@RegisterForReflection
@Data
@EqualsAndHashCode(callSuper = true)
public class ChangeSetRecord extends BaseModel {

   @NotNull
   protected String realm;

   @NotNull
   protected String dbFromVersion;
   protected int dbFromVersionInt;

   @NotNull
   protected String dbToVersion;
   protected int dbToVersionInt;

   protected int priority= 100;

   @NotNull
   protected String changeSetName;
   /**
    * Version of the changeSet definition applied.
    */
   protected int changeSetVersion = 1;
   /**
    * Checksum of the changeSet implementation.
    */
   protected String checksum;
   @NotNull
   protected String author;
   protected String description;

   @NotNull
   protected String scope;
   protected boolean successful;
   protected String errorMsg;
   protected Date lastExecutedDate;

   public String getRealm() {
      return realm;
   }

   public void setRealm(String realm) {
      this.realm = realm;
   }

   public String getDbFromVersion() {
      return dbFromVersion;
   }

   public void setDbFromVersion(String dbFromVersion) {
      this.dbFromVersion = dbFromVersion;
   }

   public int getDbFromVersionInt() {
      return dbFromVersionInt;
   }

   public void setDbFromVersionInt(int dbFromVersionInt) {
      this.dbFromVersionInt = dbFromVersionInt;
   }

   public String getDbToVersion() {
      return dbToVersion;
   }

   public void setDbToVersion(String dbToVersion) {
      this.dbToVersion = dbToVersion;
   }

   public int getDbToVersionInt() {
      return dbToVersionInt;
   }

   public void setDbToVersionInt(int dbToVersionInt) {
      this.dbToVersionInt = dbToVersionInt;
   }

   public int getPriority() {
      return priority;
   }

   public void setPriority(int priority) {
      this.priority = priority;
   }

   public String getChangeSetName() {
      return changeSetName;
   }

   public void setChangeSetName(String changeSetName) {
      this.changeSetName = changeSetName;
   }

   public int getChangeSetVersion() {
      return changeSetVersion;
   }

   public void setChangeSetVersion(int changeSetVersion) {
      this.changeSetVersion = changeSetVersion;
   }

   public String getChecksum() {
      return checksum;
   }

   public void setChecksum(String checksum) {
      this.checksum = checksum;
   }

   public String getAuthor() {
      return author;
   }

   public void setAuthor(String author) {
      this.author = author;
   }

   public String getDescription() {
      return description;
   }

   public void setDescription(String description) {
      this.description = description;
   }

   public String getScope() {
      return scope;
   }

   public void setScope(String scope) {
      this.scope = scope;
   }

   public boolean isSuccessful() {
      return successful;
   }

   public void setSuccessful(boolean successful) {
      this.successful = successful;
   }

   public String getErrorMsg() {
      return errorMsg;
   }

   public void setErrorMsg(String errorMsg) {
      this.errorMsg = errorMsg;
   }

   public Date getLastExecutedDate() {
      return lastExecutedDate;
   }

   public void setLastExecutedDate(Date lastExecutedDate) {
      this.lastExecutedDate = lastExecutedDate;
   }


   @Override
   public String bmFunctionalArea() {
      return "MIGRATION";
   }

   @Override
   public String bmFunctionalDomain() {
      return "CHANGE_SET_RECORD";
   }


}
