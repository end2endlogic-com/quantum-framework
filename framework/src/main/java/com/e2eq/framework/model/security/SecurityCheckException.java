package com.e2eq.framework.model.security;

public class SecurityCheckException extends RuntimeException {
   SecurityCheckResponse response;

   public SecurityCheckException(SecurityCheckResponse response) {
      super("principal:" + " as " + response.getPrincipalContext().getUserId() + " has permissions missing to execution action:" + response.getResourceContext().getAction()  + " on fd:" + response.getResourceContext().getFunctionalDomain() + " in area:" + response.getResourceContext().getArea());
      this.response = response;
   }

   public SecurityCheckResponse getResponse () {
      return response;
   }

   public void setResponse (SecurityCheckResponse response) {
      this.response = response;
   }
}
