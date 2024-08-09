package com.e2eq.framework.model.persistent.migration.base;

import com.e2eq.framework.model.persistent.base.BaseModel;
import dev.morphia.annotations.*;
import io.quarkus.runtime.annotations.RegisterForReflection;

import jakarta.validation.constraints.NotNull;
import java.util.Date;

@Indexes({
      @Index(
         fields= @Field("changeSetName"),
         options=@IndexOptions(unique=true, name="unique-changeset-name")
      )
})
@Entity
@RegisterForReflection
public class ChangeSetRecord extends BaseModel {

   @NotNull
   protected String realm;

   @NotNull
   protected Double dbFromVersion;

   @NotNull
   protected Double dbToVersion;
   protected int priority= 100;

   @NotNull
   protected String changeSetName;
   @NotNull
   protected String author;
   protected String description;

   @NotNull
   protected String scope;
   protected boolean successful;
   protected String errorMsg;
   protected Date lastExecutedDate;


   public Double getDbFromVersion () {
      return dbFromVersion;
   }

   public void setDbFromVersion (Double dbFromVersion) {
      this.dbFromVersion = dbFromVersion;
   }

   public Double getDbToVersion () {
      return dbToVersion;
   }

   public void setDbToVersion (Double dbToVersion) {
      this.dbToVersion = dbToVersion;
   }

   public String getAuthor () {
      return author;
   }

   public void setAuthor (String author) {
      this.author = author;
   }

   public String getDescription () {
      return description;
   }

   public void setDescription (String description) {
      this.description = description;
   }

   public String getScope () {
      return scope;
   }

   public void setScope (String scope) {
      this.scope = scope;
   }

   public int getPriority () {
      return priority;
   }

   public void setPriority (int priority) {
      this.priority = priority;
   }

   public String getChangeSetName () {
      return changeSetName;
   }

   public void setChangeSetName (String changeSetName) {
      this.changeSetName = changeSetName;
   }

   public String getRealm () {
      return realm;
   }

   public void setRealm (String realm) {
      this.realm = realm;
   }

   public Date getLastExecutedDate () {
      return lastExecutedDate;
   }

   public void setLastExecutedDate (Date lastExecutedDate) {
      this.lastExecutedDate = lastExecutedDate;
   }

   public boolean isSuccessful () {
      return successful;
   }

   public void setSuccessful (boolean successful) {
      this.successful = successful;
   }

   public String getErrorMsg () {
      return errorMsg;
   }

   public void setErrorMsg (String errorMsg) {
      this.errorMsg = errorMsg;
   }

   @Override
   public String bmFunctionalArea() {
      return "MIGRATION";
   }

   @Override
   public String bmFunctionalDomain() {
      return "CHANGE_SET_RECORD";
   }

   @Override
   public boolean equals (Object o) {
      if (this == o) return true;
      if (!(o instanceof ChangeSetRecord)) return false;

      ChangeSetRecord that = (ChangeSetRecord) o;

      if (priority != that.priority) return false;
      if (successful != that.successful) return false;
      if (id != null ? !id.equals(that.id) : that.id != null) return false;
      if (realm != null ? !realm.equals(that.realm) : that.realm != null) return false;
      if (dbFromVersion != null ? !dbFromVersion.equals(that.dbFromVersion) : that.dbFromVersion != null) return false;
      if (dbToVersion != null ? !dbToVersion.equals(that.dbToVersion) : that.dbToVersion != null) return false;
      if (changeSetName != null ? !changeSetName.equals(that.changeSetName) : that.changeSetName != null) return false;
      if (author != null ? !author.equals(that.author) : that.author != null) return false;
      if (description != null ? !description.equals(that.description) : that.description != null) return false;
      if (scope != null ? !scope.equals(that.scope) : that.scope != null) return false;
      if (errorMsg != null ? !errorMsg.equals(that.errorMsg) : that.errorMsg != null) return false;
      return lastExecutedDate != null ? lastExecutedDate.equals(that.lastExecutedDate) : that.lastExecutedDate == null;
   }

   @Override
   public int hashCode () {
      int result = id != null ? id.hashCode() : 0;
      result = 31 * result + (realm != null ? realm.hashCode() : 0);
      result = 31 * result + (dbFromVersion != null ? dbFromVersion.hashCode() : 0);
      result = 31 * result + (dbToVersion != null ? dbToVersion.hashCode() : 0);
      result = 31 * result + priority;
      result = 31 * result + (changeSetName != null ? changeSetName.hashCode() : 0);
      result = 31 * result + (author != null ? author.hashCode() : 0);
      result = 31 * result + (description != null ? description.hashCode() : 0);
      result = 31 * result + (scope != null ? scope.hashCode() : 0);
      result = 31 * result + (successful ? 1 : 0);
      result = 31 * result + (errorMsg != null ? errorMsg.hashCode() : 0);
      result = 31 * result + (lastExecutedDate != null ? lastExecutedDate.hashCode() : 0);
      return result;
   }
}
