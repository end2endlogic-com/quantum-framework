package com.e2eq.framework.util;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotNull;
import java.util.Date;
import java.util.Optional;

public class TestBean {
   // Have to add these annotations to have the property required
   @JsonProperty(required = true)
   @NotNull
   private String name;

   private String id;
   private Date creationDate;
   private int hits;
   private Optional<String> somethingOptional;

   public String getName () {
      return name;
   }

   public void setName (String name) {
      this.name = name;
   }

   public String getId () {
      return id;
   }

   public void setId (String id) {
      this.id = id;
   }

   public Date getCreationDate () {
      return creationDate;
   }

   public void setCreationDate (Date creationDate) {
      this.creationDate = creationDate;
   }

   public int getHits () {
      return hits;
   }

   public void setHits (int hits) {
      this.hits = hits;
   }

   public Optional<String> getSomethingOptional () {
      return somethingOptional;
   }

   public void setSomethingOptional (String somethingOptional) {
      this.somethingOptional = Optional.ofNullable(somethingOptional);
   }

   @Override
   public boolean equals (Object o) {
      if (this == o) return true;
      if (!(o instanceof TestBean)) return false;

      TestBean testBean = (TestBean) o;

      if (hits != testBean.hits) return false;
      if (name != null ? !name.equals(testBean.name) : testBean.name != null) return false;
      if (id != null ? !id.equals(testBean.id) : testBean.id != null) return false;
      if (creationDate != null ? !creationDate.equals(testBean.creationDate) : testBean.creationDate != null)
         return false;
      return somethingOptional != null ? somethingOptional.equals(testBean.somethingOptional) :
                testBean.somethingOptional == null;
   }

   @Override
   public int hashCode () {
      int result = name != null ? name.hashCode() : 0;
      result = 31 * result + (id != null ? id.hashCode() : 0);
      result = 31 * result + (creationDate != null ? creationDate.hashCode() : 0);
      result = 31 * result + hits;
      result = 31 * result + (somethingOptional != null ? somethingOptional.hashCode() : 0);
      return result;
   }
}
