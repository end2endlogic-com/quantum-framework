package com.e2eq.framework.rest.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;


public class RegistrationRequest {
   protected boolean acceptedTerms;
   @JsonProperty(required = true)
   protected @NotNull String fname;
   @JsonProperty(required = true)
   protected @NotNull String lname;
   @JsonProperty(required = true)
   protected @NotNull  String telephone;
   @JsonProperty(required = true)
   protected @NotNull  String companyName;
   @JsonProperty(required = true)
   protected @NotNull  @Email String email;
   @JsonProperty(required = true)
   protected @NotNull  String password;

   public boolean isAcceptedTerms () {
      return acceptedTerms;
   }

   public void setAcceptedTerms (boolean acceptedTerms) {
      this.acceptedTerms = acceptedTerms;
   }


   public String getTelephone () {
      return telephone;
   }

   public void setTelephone (String telephone) {
      this.telephone = telephone;
   }

   public String getCompanyName () {
      return companyName;
   }

   public void setCompanyName (String companyName) {
      this.companyName = companyName;
   }

   public String getEmail () {
      return email;
   }

   public void setEmail (String email) {
      this.email = email;
   }

   public String getPassword () {
      return password;
   }

   public void setPassword (String password) {
      this.password = password;
   }

   public String getFname() {
      return fname;
   }

   public void setFname(String fname) {
      this.fname = fname;
   }

   public String getLname() {
      return lname;
   }

   public void setLname(String lname) {
      this.lname = lname;
   }

   @Override
   public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof RegistrationRequest that)) return false;
      return acceptedTerms == that.acceptedTerms && fname.equals(that.fname) && lname.equals(that.lname) && telephone.equals(that.telephone) && companyName.equals(that.companyName) && email.equals(that.email) && password.equals(that.password);
   }

   @Override
   public int hashCode() {
      return Objects.hash(acceptedTerms, fname, lname, telephone, companyName, email, password);
   }
}
