package com.e2eq.framework.security;

import java.util.ArrayList;
import java.util.List;

public class TestPrincipal extends TestBase{
   String id;
   String name;
   List<String> roles = new ArrayList<>();


   public String getId () {
      return id;
   }

   public void setId (String id) {
      this.id = id;
   }

   public String getName () {
      return name;
   }

   public void setName (String name) {
      this.name = name;
   }

   public List<String> getRoles () {
      return roles;
   }

   public void setRoles (List<String> roles) {
      this.roles = roles;
   }

   @Override
   public boolean equals (Object o) {
      if (this == o) return true;
      if (!(o instanceof TestPrincipal)) return false;
      if (!super.equals(o)) return false;

      TestPrincipal that = (TestPrincipal) o;

      if (id != null ? !id.equals(that.id) : that.id != null) return false;
      if (name != null ? !name.equals(that.name) : that.name != null) return false;
      return roles != null ? roles.equals(that.roles) : that.roles == null;
   }

   @Override
   public int hashCode () {
      int result = super.hashCode();
      result = 31 * result + (id != null ? id.hashCode() : 0);
      result = 31 * result + (name != null ? name.hashCode() : 0);
      result = 31 * result + (roles != null ? roles.hashCode() : 0);
      return result;
   }
}
