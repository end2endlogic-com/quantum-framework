package com.e2eq.framework.exceptions;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ValidationException;
import java.util.Set;

public class E2eqValidationException extends ValidationException {
   protected Set<ConstraintViolation<Object>> violationSet;
   protected String jsonData;

   public E2eqValidationException(Set<ConstraintViolation<Object>> violationSet, String jsonData) {
      super();
      this.violationSet = violationSet;
      this.jsonData = jsonData;
   }

   public E2eqValidationException(String message, Throwable cause) {
      super(message, cause);
   }

   public E2eqValidationException(Throwable cause) {
      super(cause);
   }

   public Set<ConstraintViolation<Object>> getViolationSet () {
      return violationSet;
   }

   public void setViolationSet (Set<ConstraintViolation<Object>> violationSet) {
      this.violationSet = violationSet;
   }

   public String getJsonData() {
      return jsonData;
   }

   public void setJsonData(String jsonData) {
      this.jsonData = jsonData;
   }

   @Override
   public String getMessage() {
     // using a string template add in the violation details into the message
      if (violationSet != null && violationSet.size() > 0) {
         StringBuilder sb = new StringBuilder();
         for (ConstraintViolation<?> violation : violationSet) {
            sb.append(violation.getMessage()).append(" : ").append(violation.getPropertyPath()).append(" : ").append(violation.getRootBean().getClass().getName()).append("\n");
         }
         return sb.toString();
      } else {
         return super.getMessage();
      }
   }

   @Override
   public String toString() {
      return "QValidationException{" +
              "violationSet=" + violationSet +
              ", jsonData='" + jsonData + '\'' +
              '}';
   }
}
