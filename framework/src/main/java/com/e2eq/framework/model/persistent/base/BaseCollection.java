package com.e2eq.framework.model.persistent.base;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
public @Data @NoArgsConstructor class  BaseCollection <T> {
   public static enum SortType {
      ASCENDING,
      DECENDING,
      NONE
   };

   protected int offset;
   protected int rowCount;
   protected int totalRowsAvailable;
   protected List<String> columns = new ArrayList<>();
   protected SortType sortType;
   protected List<T> rows;
}
