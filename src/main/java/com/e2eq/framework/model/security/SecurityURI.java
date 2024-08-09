package com.e2eq.framework.model.security;

import io.quarkus.runtime.annotations.RegisterForReflection;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@RegisterForReflection
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

   public String toString() {
      return header.toString() + ":" + body.toString();
   }

   @Override
   public boolean equals (Object o) {
      if (this == o) return true;
      if (!(o instanceof SecurityURI)) return false;

      SecurityURI that = (SecurityURI) o;

      if (!header.equals(that.header)) return false;
      return body.equals(that.body);
   }

   @Override
   public int hashCode () {
      int result = header.hashCode();
      result = 31 * result + body.hashCode();
      return result;
   }
}
