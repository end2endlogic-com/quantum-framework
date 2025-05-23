package com.e2eq.framework.rest.models;

import com.e2eq.framework.model.persistent.base.SortField;
import dev.morphia.annotations.Transient;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.Date;
import java.util.List;

@RegisterForReflection
@Data
@EqualsAndHashCode
@NoArgsConstructor
public  class Collection<T> {
   protected int offset;
   protected int limit;
   protected Date asOf;
   protected int sortDirection;
   protected List<String> sortFields= Collections.emptyList();

   protected List<T> rows;
   protected int rowCount;
   protected Long totalCount = null;

   protected String filter;

   @Transient
   protected UIActionList actionList = new UIActionList();

   public Collection(List<T> rows, int offset, int limit, String filter) {
      this.rows = rows;
      this.rowCount = (rows==null) ? 0 : rows.size();
      this.offset = offset;
      this.limit = limit;
      this.filter = filter;
      asOf = new Date();
   }

   public Collection(List<T> rows, int offset, int limit, String filter, Long totalCount) {
      this.rows = rows;
      this.rowCount = (rows==null) ? 0 : rows.size();
      this.totalCount = (totalCount == null) ? null : totalCount;
      this.offset = offset;
      this.limit = limit;
      this.filter = filter;
      asOf = new Date();
   }

   public Collection(List<T> rows, int offset, int limit, String filter, Long totalCount, List<SortField> sortFields) {
      this.rows = rows;
      this.rowCount = (rows==null) ? 0 : rows.size();
      this.totalCount = (totalCount == null) ? null : totalCount;
      this.offset = offset;
      this.limit = limit;
      this.filter = filter;
      this.sortFields = sortFields.stream().map(SortField::toString).toList();

      asOf = new Date();
   }

}
