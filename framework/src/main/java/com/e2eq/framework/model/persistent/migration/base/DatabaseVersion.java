package com.e2eq.framework.model.persistent.migration.base;

import com.e2eq.framework.model.persistent.base.BaseModel;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.Date;

@Entity
@RegisterForReflection
@NoArgsConstructor
@EqualsAndHashCode (callSuper = true)
public@Data class DatabaseVersion extends BaseModel {


   protected String currentVersion;
   protected Date since;


   @Override
   public String bmFunctionalArea() {
      return "MIGRATION";
   }

   @Override
   public String bmFunctionalDomain() {
      return "DATABASE_VERSION";
   }





}
