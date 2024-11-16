package com.e2eq.framework.exceptions;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ValidationException;
import java.util.Set;

public class E2eqValidationException extends ValidationException {
   private static final long serialVersionUID = 1L;
   protected Set<ConstraintViolation<Object>> violationSet;

   protected String jsonData;

   public E2eqValidationException(String message) {
      super(message);
   }

   public E2eqValidationException() {
      super();
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
   public String toString() {
      return "QValidationException{" +
              "violationSet=" + violationSet +
              ", jsonData='" + jsonData + '\'' +
              '}';
   }
}
