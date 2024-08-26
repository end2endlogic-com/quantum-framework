package com.e2eq.framework.model.persistent.security;

import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Reference;
import io.quarkus.runtime.annotations.RegisterForReflection;
import com.e2eq.framework.model.persistent.base.FullBaseModel;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@Entity("account")
@RegisterForReflection
public class Account extends FullBaseModel {
   String accountNumber;

   @Reference
   Organization owningOrg;

   public Account() {
      super();
   }


   @Override
   public void prePersist () {
      if (displayName == null ) {
         displayName = owningOrg.getDisplayName();
      }
   }

   @Override
   public String bmFunctionalArea() {
      return "SECURITY";
   }

   @Override
   public String bmFunctionalDomain() {
      return "ACCOUNT";
   }

   public String getAccountNumber () {
      return accountNumber;
   }

   public void setAccountNumber (@NotNull String accountNumber) {
      this.accountNumber = accountNumber;
   }

   public Organization getOwningOrg () {
      return owningOrg;
   }

   public void setOwningOrg (@NotNull @Valid Organization owningOrg) {
      if (owningOrg.getId() == null) {
         throw new IllegalArgumentException("owning org must have been persisted prior to this call and thus have a non null id");
      }
      this.owningOrg = owningOrg;
   }

   @Override
   public boolean equals (Object o) {
      if (this == o) return true;
      if (!(o instanceof Account)) return false;
      if (!super.equals(o)) return false;

      Account account = (Account) o;

      if (accountNumber != null ? !accountNumber.equals(account.accountNumber) : account.accountNumber != null)
         return false;
      return owningOrg != null ? owningOrg.equals(account.owningOrg) : account.owningOrg == null;
   }

   @Override
   public int hashCode () {
      int result = super.hashCode();
      result = 31 * result + (accountNumber != null ? accountNumber.hashCode() : 0);
      result = 31 * result + (owningOrg != null ? owningOrg.hashCode() : 0);
      return result;
   }
}
