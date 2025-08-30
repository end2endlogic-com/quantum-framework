package com.e2eq.framework.model.security;

import dev.morphia.annotations.Entity;
import dev.morphia.annotations.PrePersist;
import dev.morphia.annotations.Reference;
import io.quarkus.runtime.annotations.RegisterForReflection;
import com.e2eq.framework.model.persistent.base.FullBaseModel;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Represents a user account that belongs to an {@link Organization}.
 * Each account is identified by a 10 digit account number.
 */
@Entity("account")
@RegisterForReflection
public class Account extends FullBaseModel {

   @Pattern(regexp = "^\\d{10}$", message = "accountNumber must be exactly 10 digits")
   String accountNumber;

   @Reference
   Organization owningOrg;

   /**
    * Creates a new empty account instance.
    */
   public Account() {
      super();
   }


   /**
    * Ensure the display name is set to the owning organisation's display name
    * before the entity is persisted.
    */
   @PrePersist
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

   /**
    * Returns the ten digit account number.
    */
   public String getAccountNumber () {
      return accountNumber;
   }

   /**
    * Sets the account number.
    *
    * @param accountNumber ten digit identifier for the account
    */
   public void setAccountNumber (@NotNull String accountNumber) {
      this.accountNumber = accountNumber;
   }

   /**
    * Returns the organisation that owns this account.
    */
   public Organization getOwningOrg () {
      return owningOrg;
   }

   /**
    * Assigns the organisation that owns this account.
    *
    * @param owningOrg organisation that must already be persisted
    */
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
