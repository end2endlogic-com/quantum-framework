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


   @Override
   public String bmFunctionalArea() {
      return "MIGRATION";
   }

   @Override
   public String bmFunctionalDomain() {
      return "CHANGE_SET_RECORD";
   }


}
