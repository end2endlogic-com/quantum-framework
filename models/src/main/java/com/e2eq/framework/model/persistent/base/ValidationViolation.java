package com.e2eq.framework.model.persistent.base;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.NotNull;

@RegisterForReflection
public class ValidationViolation {
   /**
    This is the bean path to the property that this violation refers to.  Format
    for this will be {ClasName}.{property}...{property} with the leave node
    being the final property in question.  This is modeled from Hibernates
    Constraint framework.
    */
   protected String propertyPath;
   protected String violationDescription;
   protected Object invalidValue;

   public ValidationViolation() {

   }

   public ValidationViolation(@NotNull String propertyPath, @NotNull String violationDescription) {
      this.propertyPath = propertyPath;
      this.violationDescription = violationDescription;
   }

   public String getPropertyPath () {
      return propertyPath;
   }

   public void setPropertyPath (String propertyPath) {
      this.propertyPath = propertyPath;
   }

   public String getViolationDescription () {
      return violationDescription;
   }

   public void setViolationDescription (String violationDescription) {
      this.violationDescription = violationDescription;
   }

   public Object getInvalidValue () {
      return invalidValue;
   }

   public void setInvalidValue (Object invalidValue) {
      this.invalidValue = invalidValue;
   }

   @Override
   public boolean equals (Object o) {
      if (this == o) return true;
      if (!(o instanceof ValidationViolation)) return false;

      ValidationViolation that = (ValidationViolation) o;

      if (propertyPath != null ? !propertyPath.equals(that.propertyPath) : that.propertyPath != null) return false;
      if (violationDescription != null ? !violationDescription.equals(that.violationDescription) :
             that.violationDescription != null)
         return false;
      return invalidValue != null ? invalidValue.equals(that.invalidValue) : that.invalidValue == null;
   }

   @Override
   public int hashCode () {
      int result = propertyPath != null ? propertyPath.hashCode() : 0;
      result = 31 * result + (violationDescription != null ? violationDescription.hashCode() : 0);
      result = 31 * result + (invalidValue != null ? invalidValue.hashCode() : 0);
      return result;
   }
}
