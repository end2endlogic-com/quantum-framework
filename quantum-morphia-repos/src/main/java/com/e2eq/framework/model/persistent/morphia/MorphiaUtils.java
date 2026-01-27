package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.grammar.BIAPIQueryLexer;
import com.e2eq.framework.grammar.BIAPIQueryParser;
import com.e2eq.framework.model.persistent.base.*;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import dev.morphia.query.Sort;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import dev.morphia.query.filters.RegexFilter;
import io.quarkus.logging.Log;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.text.StringSubstitutor;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class MorphiaUtils {


   public static class VariableBundle {
      public final Map<String, String> strings;
      public final Map<String, Object> objects;
      public VariableBundle(Map<String, String> strings, Map<String, Object> objects) {
         this.strings = strings;
         this.objects = objects;
      }
   }


   public static class SortParameter {
      public enum SortOrderEnum {
         ASC, DESC
      }

      private String fieldName;
      private SortParameter.SortOrderEnum sortOrder;

      public SortParameter(String fieldName, SortParameter.SortOrderEnum sortOrder) {
         this.fieldName = fieldName;
         this.sortOrder = sortOrder;
      }

      public String getFieldName() {
         return fieldName;
      }

      public SortParameter.SortOrderEnum getSortOrder() {
         return sortOrder;
      }
   }

   public static List<Sort> buildSort(List<SortParameter> sortFields, String sortField, String sortDirection) {
      List<Sort> sorts = new ArrayList<>();
      if (sortField != null && sortDirection != null) {
         if ("DESC".equals(sortDirection)) {
            sorts.add(Sort.descending(sortField));
         } else {
            sorts.add(Sort.ascending(sortField));
         }
      }

      if (sortFields != null && !sortFields.isEmpty()) {
         for (SortParameter field : sortFields) {
            if (SortParameter.SortOrderEnum.DESC.equals(field.getSortOrder())) {
               sorts.add(Sort.descending(field.getFieldName()));
            } else {
               sorts.add(Sort.ascending(field.getFieldName()));
            }
         }
      }

      return sorts;
   }


   public static Filter convertToFilter(String queryString, Class<? extends UnversionedBaseModel> modelClass) {
      return MorphiaUtils.convertToFilterWContext(queryString, null, null, modelClass );
   }

   /**
    * New entry point that plans a query and either returns a Filter (existing path) or
    * an aggregation pipeline representation. Backward-compatible: existing callers may
    * continue using convertToFilter().
    */
   public static <T extends UnversionedBaseModel> com.e2eq.framework.model.persistent.morphia.planner.PlannedQuery convertToPlannedQuery(
           String queryString, Class<T> modelClass) {
      com.e2eq.framework.model.persistent.morphia.planner.QueryPlanner planner =
              new com.e2eq.framework.model.persistent.morphia.planner.QueryPlanner();
      return planner.plan(queryString, modelClass);
   }

   /**
    * Overload that accepts paging and sort for aggregation planning.
    */
   public static <T extends UnversionedBaseModel> com.e2eq.framework.model.persistent.morphia.planner.PlannedQuery convertToPlannedQuery(
           String queryString,
           Class<T> modelClass,
           Integer limit,
           Integer skip,
           java.util.List<com.e2eq.framework.model.persistent.morphia.planner.LogicalPlan.SortSpec.Field> sortFields
   ) {
      com.e2eq.framework.model.persistent.morphia.planner.QueryPlanner planner =
              new com.e2eq.framework.model.persistent.morphia.planner.QueryPlanner();
      return planner.plan(queryString, modelClass, limit, skip, sortFields);
   }

   public static Map<String, String> createStandardVariableMapFrom(PrincipalContext pcontext, ResourceContext rcontext) {
      Map<String, String> variableMap = new HashMap<>();
      variableMap.put("principalId", pcontext.getUserId());
      variableMap.put("pAccountId", pcontext.getDataDomain().getAccountNum());
      variableMap.put("pTenantId", pcontext.getDataDomain().getTenantId());
      variableMap.put("systemTenantId", pcontext.getDataDomain().getTenantId()); // fallback for legacy rules
      variableMap.put("ownerId", pcontext.getDataDomain().getOwnerId());
      variableMap.put("orgRefName", pcontext.getDataDomain().getOrgRefName());
      variableMap.put("resourceId", rcontext.getResourceId());

      variableMap.put("action", rcontext.getAction());
      variableMap.put("functionalDomain", rcontext.getFunctionalDomain());
      variableMap.put("area", rcontext.getArea());
      
      // Add defaultRealm so permission filters can dynamically scope by current realm
      // This is critical for X-Realm functionality - when realm switches, filters adapt automatically
      variableMap.put("defaultRealm", pcontext.getDefaultRealm());

      // Add DomainContext-sourced variables for richer realm context
      // These provide direct access to the domain context fields (which may differ from DataDomain when X-Realm is used)
      DomainContext dc = pcontext.getDomainContext();
      if (dc != null) {
         variableMap.put("dcTenantId", dc.getTenantId());
         variableMap.put("dcOrgRefName", dc.getOrgRefName());
         variableMap.put("dcAccountId", dc.getAccountId());
         variableMap.put("dcDataSegment", String.valueOf(dc.getDataSegment()));
      }

      // Add custom properties from PrincipalContextPropertiesResolver implementations
      // These are converted to strings for use in filter string substitution
      Map<String, Object> customProps = pcontext.getCustomProperties();
      if (customProps != null && !customProps.isEmpty()) {
         for (Map.Entry<String, Object> entry : customProps.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) {
               variableMap.put(entry.getKey(), (String) value);
            } else if (value != null) {
               // For collections and other objects, convert to string representation
               variableMap.put(entry.getKey(), String.valueOf(value));
            }
         }
      }

      return variableMap;
   }

   public static VariableBundle buildVariableBundle(
           PrincipalContext pcontext,
           ResourceContext rcontext,
           Map<String, Object> extraObjects
   ) {
      Map<String, String> s = createStandardVariableMapFrom(pcontext, rcontext);
      Map<String, Object> o = new HashMap<>();
      o.putAll(s);

      // Add custom properties to objects map (preserving their original types for typed filter construction)
      // This allows collections to be used directly in $in queries
      Map<String, Object> customProps = pcontext.getCustomProperties();
      if (customProps != null && !customProps.isEmpty()) {
         o.putAll(customProps);
      }

      if (extraObjects != null) {
         o.putAll(extraObjects);
         extraObjects.forEach((k, v) -> {
            if (!s.containsKey(k)) {
               s.put(k, v == null ? null : String.valueOf(v));
            }
         });
      }
      return new VariableBundle(s, o);
   }

   public static Filter convertToFilter(String queryString, VariableBundle vars, Class<? extends UnversionedBaseModel> modelClass) {
      Objects.requireNonNull(modelClass, "Model class cannot be null");
      String normalized = normalizeOperators(queryString);
      Optional<ParseTree> otree = validateQueryString(normalized);
      ParseTree tree = otree.orElseThrow(() -> new IllegalArgumentException("syntax error in query string"));
      ParseTreeWalker walker = new ParseTreeWalker();
      StringSubstitutor sub = new StringSubstitutor(vars.strings);
      QueryToFilterListener listener = new QueryToFilterListener(vars.objects, vars.strings, sub, modelClass);
      walker.walk(listener, tree);
      return listener.getFilter();
   }

   private static String normalizeOperators(String q) {
      if (q == null) return null;
      // Legacy normalization: historically ':=[' was used for IN. Normalize to the current ':^[' form.
      // Keep this for backward compatibility with stored rules; all other operators are now handled by the grammar.
      return q.replace(":=[", ":^[");
   }


   public static Filter convertToFilter(String queryString, @NotNull Map<String, String> variableMap, StringSubstitutor sub, Class<? extends UnversionedBaseModel> modelClass) {
      Objects.requireNonNull(modelClass, "Model class cannot be null");
      if (queryString != null && !queryString.isEmpty()) {
         BIAPIQueryLexer lexer = new BIAPIQueryLexer(CharStreams.fromString(queryString));
         BIAPIQueryParser parser = new BIAPIQueryParser(new CommonTokenStream(lexer));
         parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                    int charPositionInLine, String msg, RecognitionException e) {
               throw new IllegalArgumentException("Failed to parse " + queryString + " at position "
                       + charPositionInLine + " due to " + msg, e);
            }
         });
         ParseTree tree = parser.query();
         ParseTreeWalker walker = new ParseTreeWalker();
         QueryToFilterListener listener;
         if (sub == null) {
            listener = new QueryToFilterListener(variableMap, modelClass);
         } else {
            listener = new QueryToFilterListener(variableMap, sub, modelClass);
         }
         walker.walk(listener, tree);
         return listener.getFilter();
      } else {
         throw new IllegalArgumentException("Null or empty query string is not valid");
      }
   }

   public static Optional<ParseTree> validateQueryString(String queryString) {

      if (queryString == null || queryString.isBlank()) {
         Log.warnf("Validation of query string failed: null or empty query string");
         return Optional.empty();
      }

      BIAPIQueryLexer lexer = new BIAPIQueryLexer(CharStreams.fromString(queryString));
      BIAPIQueryParser parser = new BIAPIQueryParser(new CommonTokenStream(lexer));
      parser.addErrorListener(new BaseErrorListener() {
         @Override
         public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                 int charPositionInLine, String msg, RecognitionException e) {
            throw new IllegalArgumentException("syntax error in query string: Failed to parse " + queryString + " at position "
                                                  + charPositionInLine + " due to " + msg, e);
         }
      });
      return Optional.of(parser.query());
   }


   public static Filter convertToFilterWContext(String queryString, PrincipalContext pcontext, ResourceContext rcontext, Class<? extends UnversionedBaseModel> modelClass)  {
      Objects.requireNonNull(modelClass, "Model class cannot be null");

      if (queryString != null && !queryString.isEmpty()) {
         /* BIAPIQueryLexer lexer = new BIAPIQueryLexer(CharStreams.fromString(queryString));
         BIAPIQueryParser parser = new BIAPIQueryParser(new CommonTokenStream(lexer));
         parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                    int charPositionInLine, String msg, RecognitionException e) {
               throw new IllegalArgumentException("syntax error in query string: Failed to parse " + queryString + " at position "
                       + charPositionInLine + " due to " + msg, e);
            }
         });
         ParseTree tree = parser.query(); */
         Optional<ParseTree> otree = validateQueryString(queryString);
         if (!otree.isPresent()) {
            throw new IllegalArgumentException("syntax error in query string: is either null or empty but is required not to be");
         }
         ParseTree tree = otree.get();
         ParseTreeWalker walker = new ParseTreeWalker();

         QueryToFilterListener listener;
         if (pcontext == null || rcontext == null) {
            listener = new QueryToFilterListener(modelClass);
         } else {
            listener = new QueryToFilterListener(pcontext, rcontext, modelClass);
         }
         walker.walk(listener, tree);

         return listener.getFilter();
      } else {
         throw new IllegalArgumentException("Null or empty query string is not valid");
      }

   }

   public Filter buildDynamicSearchQuery(Class<?> clazz, DynamicSearchRequest searchRequest) {
      List<Filter> filters = new ArrayList<>();

      DynamicAttributeSet systemFields = searchRequest.getSystemFields();
      if (systemFields != null && systemFields.getAttributes() != null) {
         // Add system fields to the query
      }

      DynamicAttributeSet attributeSet = searchRequest.getSearchFields();
      Filter finalFilter;
      for (DynamicAttribute attribute : attributeSet.getAttributes()) {
         Filter f;
         if (!searchRequest.isExactMatches() || attribute.getType().equals(DynamicAttributeType.Regex)) {
            RegexFilter rf = Filters.regex(attribute.getName(), attribute.getValue().toString());
            if (searchRequest.isCaseInsensitive()) {
               f = rf.caseInsensitive();
            } else {
               f = rf;
            }
            filters.add(f);
         } else if (!searchRequest.isExactMatches() ||
                 attribute.getType().equals(DynamicAttributeType.Exclude)) {
            f = Filters.regex(attribute.getName(), attribute.getValue().toString()).not();
            filters.add(f);
         }
      }

      if (searchRequest.getSearchCondition() == SearchCondition.AND) {
         finalFilter = Filters.and(filters.toArray(new Filter[filters.size()]));
      } else if (searchRequest.getSearchCondition() == SearchCondition.OR) {
         finalFilter = Filters.or(filters.toArray(new Filter[filters.size()]));
      } else {
         throw new NotImplementedException("Unsupported search condition: " + searchRequest.getSearchCondition());
      }

      return finalFilter;
   }
}
