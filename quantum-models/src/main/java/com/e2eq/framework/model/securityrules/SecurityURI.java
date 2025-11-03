package com.e2eq.framework.model.securityrules;

import io.quarkus.runtime.annotations.RegisterForReflection;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@RegisterForReflection
@ToString
@EqualsAndHashCode
public class SecurityURI {
   @Valid @NotNull SecurityURIHeader header;
   @Valid @NotNull SecurityURIBody   body;

   public SecurityURI() {
      header = new SecurityURIHeader();
      body = new SecurityURIBody();
   }

   public SecurityURI(@NotNull SecurityURIHeader header, @NotNull SecurityURIBody body) {
      this.header = header;
      this.body = body;
   }

   public SecurityURIHeader getHeader () {
      return header;
   }

   public void setHeader (SecurityURIHeader header) {
      this.header = header;
   }

   public SecurityURIBody getBody () {
      return body;
   }

   public void setBody (SecurityURIBody body) {
      this.body = body;
   }

   public SecurityURI clone() {
      SecurityURIHeader nh = header.clone();
      SecurityURIBody nb = body.clone();
      return new SecurityURI(nh, nb);
   }

   public String getURIString() {
      return String.format("%s|%s", header.getURIString(), body.getURIString());
   }


}
