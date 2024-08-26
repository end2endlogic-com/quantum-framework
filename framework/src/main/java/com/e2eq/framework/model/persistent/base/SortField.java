package com.e2eq.framework.model.persistent.base;

public class SortField {
   public enum SortDirection {
      ASC,
      DESC
   }

   String fieldName;
   SortDirection sortDirection = SortDirection.ASC;

   public SortField() {
   }

   public SortField(String fieldName, SortDirection sortDirection ) {
      this.fieldName = fieldName;
      this.sortDirection = sortDirection;
   }

   public String getFieldName () {
      return fieldName;
   }

   public void setFieldName (String fieldName) {
      this.fieldName = fieldName;
   }

   public SortDirection getSortDirection () {
      return sortDirection;
   }

   public void setSortDirection (SortDirection sortDirection) {
      this.sortDirection = sortDirection;
   }

   @Override
   public boolean equals (Object o) {
      if (this == o) return true;
      if (!(o instanceof SortField)) return false;

      SortField sortField = (SortField) o;

      if (fieldName != null ? !fieldName.equals(sortField.fieldName) : sortField.fieldName != null) return false;
      return sortDirection == sortField.sortDirection;
   }

   @Override
   public int hashCode () {
      int result = fieldName != null ? fieldName.hashCode() : 0;
      result = 31 * result + (sortDirection != null ? sortDirection.hashCode() : 0);
      return result;
   }
}
