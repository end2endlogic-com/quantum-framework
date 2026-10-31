package com.e2eq.framework.model.securityrules;

public class SecurityCheckException extends RuntimeException {
   SecurityCheckResponse response;

   public SecurityCheckException(SecurityCheckResponse response) {
      super("principal:" + " as " + response.getPrincipalContext().getUserId() + " has permissions missing to execution action:" + response.getResourceContext().getAction()  + " on fd:" + response.getResourceContext().getFunctionalDomain() + " in area:" + response.getResourceContext().getArea());
      this.response = response;
   }

   /**
    * Denial with an explicit, specific message (e.g. a write into a data domain the caller's
    * rules do not allow).
    */
   public SecurityCheckException(SecurityCheckResponse response, String message) {
      super(message);
      this.response = response;
   }

   public SecurityCheckResponse getResponse () {
      return response;
   }

   public void setResponse (SecurityCheckResponse response) {
      this.response = response;
   }
}
