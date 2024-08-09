package com.e2eq.framework.rest.models;

import com.e2eq.framework.model.persistent.base.UIActionList;
import dev.morphia.annotations.Transient;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;

import java.util.Collections;
import java.util.Date;
import java.util.List;

@RegisterForReflection
public @Data class Collection<T> {
   protected int offset;
   protected int limit;
   protected Date asOf;
   protected int sortDirection;
   protected List<String> sortFields= Collections.emptyList();

   protected List<T> rows;

   protected String filter;

   @Transient
   protected UIActionList actionList = new UIActionList();

   public Collection(List<T> rows, int offset, int limit, String filter) {
      this.rows = rows;
      this.offset = offset;
      this.limit = limit;
      this.filter = filter;
      asOf = new Date();
   }

}
