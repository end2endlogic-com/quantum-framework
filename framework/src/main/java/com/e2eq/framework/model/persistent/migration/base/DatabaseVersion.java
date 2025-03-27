package com.e2eq.framework.model.persistent.migration.base;

import com.e2eq.framework.model.persistent.base.BaseModel;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.*;
import org.semver4j.Semver;

import java.util.Date;

@Entity
@RegisterForReflection
@NoArgsConstructor
@EqualsAndHashCode (callSuper = true)
@ToString(callSuper = true)
public@Data class DatabaseVersion extends BaseModel {

   // use setCurrentVersionString vs. setting the sem version directly
   @Getter
   protected Semver currentSemVersion;
   protected String currentVersionString;
   protected int currentVersionInt;
   protected Date lastUpdated;


   public void setCurrentVersionString(String currentVersion) {
      Semver semver = Semver.parse(currentVersion);
      if (semver == null) {
         throw new IllegalArgumentException(String.format(" the current version string: %s is not parsable, check semver4j for more details about string format", currentVersion));
      }
      this.currentVersionInt = (semver.getMajor() *100) + (semver.getMinor() * 10) + semver.getPatch();
      this.currentSemVersion = semver;
      currentVersionString = currentVersion;
   }


   @Override
   public String bmFunctionalArea() {
      return "MIGRATION";
   }

   @Override
   public String bmFunctionalDomain() {
      return "DATABASE_VERSION";
   }





}
