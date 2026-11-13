package com.e2eq.framework.model.security;

import com.e2eq.framework.model.persistent.base.BaseModel;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * A functional area of an application's policy vocabulary: area → {@link FunctionalDomain} →
 * {@link FunctionalAction}. An area belongs to exactly one application; others may only borrow
 * its domains ({@link VocabularyReference}).
 */
@Entity("functionalArea")
@RegisterForReflection
@Data
@EqualsAndHashCode(callSuper = true)
public class FunctionalArea extends BaseModel {

   /** The application that owns this area. */
   protected String applicationId;

   protected String description;

   @Override
   public String bmFunctionalDomain() {
      return "FUNCTIONAL_AREA";
   }

   @Override
   public String bmFunctionalArea() {
      return "SECURITY";
   }
}
