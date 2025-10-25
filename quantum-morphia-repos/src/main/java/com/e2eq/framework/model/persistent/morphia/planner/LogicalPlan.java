package com.e2eq.framework.model.persistent.morphia.planner;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.metadata.JoinSpec;
import dev.morphia.query.filters.Filter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Minimal logical plan for aggregation planning.
 * v1 carries root type, optional root projection, expansions, and paging/sort shells.
 */
public class LogicalPlan {
    public final Class<? extends UnversionedBaseModel> rootType;
    public final PlannerProjection rootProjection; // null means default projection
    public final List<Expand> expansions;
    public final SortSpec sort; // not populated in v1
    public final PageSpec page; // not populated in v1
    public final Filter rootFilter; // optional root filter compiled from the query (sans expand)

    public LogicalPlan(Class<? extends UnversionedBaseModel> rootType,
                       PlannerProjection rootProjection,
                       List<Expand> expansions,
                       SortSpec sort,
                       PageSpec page) {
        this(rootType, rootProjection, expansions, sort, page, null);
    }

    public LogicalPlan(Class<? extends UnversionedBaseModel> rootType,
                       PlannerProjection rootProjection,
                       List<Expand> expansions,
                       SortSpec sort,
                       PageSpec page,
                       Filter rootFilter) {
        this.rootType = rootType;
        this.rootProjection = rootProjection;
        this.expansions = expansions == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(expansions));
        this.sort = sort;
        this.page = page;
        this.rootFilter = rootFilter;
    }

    public static class Expand {
        public final String path; // e.g., "customer" or "items[*].product"
        public final int depth;   // default 1
        public final PlannerProjection projection; // null means default
        public final boolean array; // convenience hint
        public final JoinSpec join; // may be null in early planning
        public Expand(String path, int depth, PlannerProjection projection, boolean array, JoinSpec join) {
            this.path = path;
            this.depth = depth;
            this.projection = projection;
            this.array = array;
            this.join = join;
        }
    }

    public static class PlannerProjection {
        public final java.util.Set<String> include;
        public final java.util.Set<String> exclude;
        public final boolean includeMode;
        public PlannerProjection(java.util.Set<String> include, java.util.Set<String> exclude, boolean includeMode) {
            this.include = include == null ? java.util.Set.of() : java.util.Set.copyOf(include);
            this.exclude = exclude == null ? java.util.Set.of() : java.util.Set.copyOf(exclude);
            this.includeMode = includeMode;
        }
    }

    public static class SortSpec {
        public static class Field {
            public final String name;
            public final int dir; // 1 for ASC, -1 for DESC
            public Field(String name, int dir) { this.name = name; this.dir = dir; }
        }
        public final java.util.List<Field> fields;
        public SortSpec(java.util.List<Field> fields) {
            this.fields = fields == null ? java.util.List.of() : java.util.List.copyOf(fields);
        }
    }
    public static class PageSpec {
        public final Integer limit;
        public final Integer skip;
        public PageSpec(Integer limit, Integer skip) {
            this.limit = limit;
            this.skip = skip;
        }
    }
}
