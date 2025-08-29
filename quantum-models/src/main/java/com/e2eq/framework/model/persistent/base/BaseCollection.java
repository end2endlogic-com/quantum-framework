package com.e2eq.framework.model.persistent.base;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Generic container for paginated, tabular results returned from repositories/services.
 * <p>
 * This class is intended to be used as a lightweight DTO that carries a page of rows along with
 * pagination and sorting metadata that UIs or API clients can use to render tables/grids.
 * Lombok's {@code @Data} provides getters/setters and other boilerplate; Quarkus {@code @RegisterForReflection}
 * allows the class to be used in native images.
 * </p>
 *
 * @param <T> the element type for each row contained in this collection
 */
@RegisterForReflection
public @Data @NoArgsConstructor class  BaseCollection <T> {
   /**
    * Sorting direction for the rows.
    * <p>
    * Note: The {@code DECENDING} constant name is kept for backward compatibility
    * (it is intentionally misspelled and should not be renamed without migration).
    * </p>
    */
   public static enum SortType {
      /** Sort rows in ascending order. */
      ASCENDING,
      /** Sort rows in descending order (legacy constant name preserved). */
      DECENDING,
      /** No sort ordering applied. */
      NONE
   };

   /**
    * Zero-based starting index of the first row included in {@link #rows} within the full result set.
    */
   protected int offset;

   /**
    * Number of rows actually included in {@link #rows} (i.e., page size for this response).
    */
   protected int rowCount;

   /**
    * Total number of rows available on the server that match the query/filter,
    * independent of paging. This enables clients to compute total pages.
    */
   protected int totalRowsAvailable;

   /**
    * Optional ordered list of column names that describe the structure of each row.
    * Useful when {@code T} is a map/array-like structure rather than a strongly-typed bean.
    */
   protected List<String> columns = new ArrayList<>();

   /**
    * Sorting preference that was applied to produce {@link #rows}.
    */
   protected SortType sortType;

   /**
    * The page of rows being returned.
    */
   protected List<T> rows;
}
