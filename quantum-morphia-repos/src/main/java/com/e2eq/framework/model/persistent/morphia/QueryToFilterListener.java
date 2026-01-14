package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.grammar.BIAPIQueryBaseListener;
import com.e2eq.framework.grammar.BIAPIQueryParser;

import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import dev.morphia.annotations.Reference;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import io.quarkus.logging.Log;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.apache.commons.text.StringSubstitutor;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import org.bson.types.ObjectId;


import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.time.LocalDate;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

public class QueryToFilterListener extends BIAPIQueryBaseListener {
    private final Map<String, Object> objectVars = new HashMap<>();
    private static final Pattern SPECIAL_REGEX_CHARS = Pattern.compile("[{}()\\[\\].+*?^$\\\\|\\-]");

    protected Stack<Filter> filterStack = new Stack<>();
    protected Stack<Integer> opTypeStack = new Stack<>();
    // markers to handle nested contexts like elemMatch where we need to scope compositions
    protected Stack<Integer> opTypeMarkers = new Stack<>();
    protected Stack<Integer> filterStackMarkers = new Stack<>();

    protected boolean complete = false;

    Map<String, String> variableMap = null;
    StringSubstitutor sub = null;

    Class<? extends UnversionedBaseModel> modelClass=null;


    public QueryToFilterListener(Map<String, String> variableMap, StringSubstitutor sub, Class<? extends UnversionedBaseModel> modelClass) {
        this.variableMap = variableMap;
        this.sub = sub != null ? sub : (variableMap != null ? new StringSubstitutor(variableMap) : null);
        this.modelClass = modelClass;
    }

    public QueryToFilterListener(Map<String, Object> objectVars, Map<String, String> variableMap, StringSubstitutor sub, Class<? extends UnversionedBaseModel> modelClass) {
        if (objectVars != null) {
            this.objectVars.putAll(objectVars);
        }
        this.variableMap = variableMap;
        this.sub = sub != null ? sub : (variableMap != null ? new StringSubstitutor(variableMap) : null);
        this.modelClass = modelClass;
    }

    public QueryToFilterListener(Map<String, String> variableMap, Class<? extends UnversionedBaseModel> modelClass) {
        this.variableMap = variableMap;
        this.sub = new StringSubstitutor(variableMap);
        this.modelClass = modelClass;
    }

    public QueryToFilterListener(PrincipalContext pcontext, ResourceContext rcontext, Class<? extends UnversionedBaseModel> modelClass) {
        this.variableMap = MorphiaUtils.createStandardVariableMapFrom(pcontext, rcontext);
        this.sub = new StringSubstitutor(variableMap);
        this.modelClass = modelClass;
    }

    public QueryToFilterListener(Class<? extends UnversionedBaseModel> modelClass) {
        this.modelClass = modelClass;
    }


    public Filter getFilter() {
        if (complete) {
            return filterStack.peek();
        } else {
            throw new IllegalStateException("Filter is incomplete");
        }
    }

    @Override
    public void enterNullExpr(BIAPIQueryParser.NullExprContext ctx) {
        if (ctx.op.getType() == BIAPIQueryParser.EQ) {
            filterStack.push(Filters.eq(ctx.field.getText(), null));
        } else if (ctx.op.getType() == BIAPIQueryParser.NEQ) {
            filterStack.push(Filters.ne(ctx.field.getText(), null));
        }
    }

    @Override
    public void exitNullExpr(BIAPIQueryParser.NullExprContext ctx) {
        super.exitNullExpr(ctx);
    }


    private String escapeRegexChars(String inputString) {
        if (inputString != null) {
            //. ^ $ * + - ? ( ) [ ] { } \ |
            return SPECIAL_REGEX_CHARS.matcher(inputString).replaceAll("\\\\$0");
        } else {
            return inputString;
        }
    }

    /*@Override
    public void enterOidExpr(BIAPIQueryParser.OidExprContext ctx) {
        String field = ctx.field.getText();
        String oid = ctx.value.getText();
        filterStack.push(Filters.eq(field, new ObjectId(oid)));
    }*/

    Object buildReference(String fieldName, String oid) {
        try {
            Field field = modelClass.getDeclaredField(fieldName);
            field.setAccessible(true);
            Annotation annotation = field.getAnnotation(Reference.class);
            if (annotation != null) {
                Object object = field.getType().getDeclaredConstructor().newInstance();
                if (object instanceof BaseModel) {
                    ((BaseModel) object).setId(new ObjectId(oid));
                }
                return object;
            } else {
                throw new IllegalArgumentException("Field:" + fieldName + " is not annotated as a reference field");
            }
        } catch (NoSuchFieldException | NoSuchMethodException | InstantiationException | IllegalAccessException |
                 InvocationTargetException e) {
            throw new IllegalStateException(e);
        }

    }

    @Override
    public void enterReferenceExpr(BIAPIQueryParser.ReferenceExprContext ctx) {
        String oid = ctx.value.getText();
        String fieldName = ctx.field.getText();
        Object reference = buildReference(fieldName, oid);
        if (ctx.op.getType() == BIAPIQueryParser.EQ) {
            filterStack.push(Filters.eq(fieldName, reference));
        } else if (ctx.op.getType() == BIAPIQueryParser.NEQ) {
            filterStack.push(Filters.ne(fieldName, reference));
        } else {
            throw new IllegalArgumentException("Operator not recognized: " + ctx.op.getText());
        }
    }

    @Override
    public void enterRegexExpr(BIAPIQueryParser.RegexExprContext ctx) {
        String field = ctx.field.getText();
        String pattern = ctx.regex().value.getText();

        String escapedValue = escapeRegexChars(ctx.regex().value.getText());
        String regex = (ctx.regex().leftW == null ? "^" : ".*")
                + escapedValue + (ctx.regex().rightW == null ? "$" : ".*");
        if (ctx.op.getType() == BIAPIQueryParser.EQ) {
            Filter regexFilter = Filters.regex(field, regex);
            filterStack.push(regexFilter);

        } else if (ctx.op.getType() == BIAPIQueryParser.NEQ) {
            Filter regexFilter = Filters.nor(Filters.regex(field, pattern));
            filterStack.push(regexFilter);
        } else {
            throw new IllegalArgumentException("Operator not recognized: " + ctx.op.getText());
        }
    }


    // Track nested query depth (top-level = 1)
    private int queryDepth = 0;

    @Override
    public void enterQuery(BIAPIQueryParser.QueryContext ctx) {
        queryDepth++;
        complete = false;
    }

   @Override
   public void exitQuery(BIAPIQueryParser.QueryContext ctx) {
      queryDepth--;
      if (queryDepth == 0) {
         if (!opTypeStack.isEmpty() || filterStack.size() > 1) {
            buildComposite();
         }
         checkDone();
      }
   }

    protected void checkDone() {
        // the only remaining thing in the stack should be what we want to return
        if (filterStack.size() != 1) {
            Log.debug("Filter stack is !=1");
            for (Filter f : filterStack) {
                Log.debugf("   Filter: %s", f.toString());
            }
            throw new IllegalStateException("Criteria stack not 1 and end of build? size:" + filterStack.size());
        }

        if (Log.isDebugEnabled() && !filterStack.isEmpty()) {
            Log.debugf("-- Additional Filters based upon rules:%d--",filterStack.size());
            for (Filter f : filterStack) {
                Log.debugf("    Name:%s Field:%s Value:%s", f.getName(), f.getField(), (f.getValue() == null)? "NULL" : f.getValue().toString());
            }
            Log.debug("--------");
        }

        complete = true;
    }

    protected void buildComposite() {
        buildCompositeSince(0, 0);
    }

    // Build the composite for the portion of the stacks added since the given markers
    protected void buildCompositeSince(int startOpSize, int startFilterSize) {
       List<Filter> andFilters = new ArrayList<>();
       List<Filter> orFilters = new ArrayList<>();
       if (Log.isDebugEnabled()) {
           Log.debug("buildCompositeSince: startOp=" + startOpSize + ", startFilter=" + startFilterSize + ", opDepthBefore=" + opTypeStack.size() + ", filterDepthBefore=" + filterStack.size());
       }

       // Unwind only operators added since the marker
       while (opTypeStack.size() > startOpSize) {
          int opType = opTypeStack.pop();
          switch (opType) {
             case BIAPIQueryParser.AND: {
                if (filterStack.size() <= startFilterSize) {
                   throw new IllegalStateException("AND expects a filter pushed in inner scope, but none found");
                }
                // consume right-hand side for AND
                andFilters.add(filterStack.pop());
                break;
             }
             case BIAPIQueryParser.OR: {
                if (filterStack.size() <= startFilterSize) {
                   throw new IllegalStateException("OR expects a filter pushed in inner scope, but none found");
                }
                if (!andFilters.isEmpty()) {
                   // Finish the pending AND (right side already popped above)
                   andFilters.add(filterStack.pop()); // left side
                   orFilters.add(Filters.and(andFilters.toArray(Filter[]::new)));
                   andFilters = new ArrayList<>();
                } else {
                   // Right-hand side of the OR
                   orFilters.add(filterStack.pop());
                }
                break;
             }
             case BIAPIQueryParser.LPAREN: {
                // Close the group using only inner-scope filters
                if (filterStack.size() <= startFilterSize) {
                   throw new IllegalStateException("Group close found no inner filters");
                }
                if (andFilters.isEmpty()) {
                   orFilters.add(filterStack.pop());
                } else {
                   andFilters.add(filterStack.pop());
                   orFilters.add(Filters.and(andFilters.toArray(Filter[]::new)));
                   andFilters = new ArrayList<>();
                }

                // Push combined group back
                if (orFilters.size() == 1) {
                   filterStack.push(orFilters.get(0));
                } else if (orFilters.size() > 1) {
                   filterStack.push(Filters.or(orFilters.toArray(Filter[]::new)));
                } else {
                   throw new IllegalStateException("OrCriteria is empty; expected at least one filter in group");
                }
                orFilters = new ArrayList<>();
                break;
             }
             default:
                throw new IllegalArgumentException("Unsupported operation:" + opType);
          }
       }

       // After unwinding the inner ops, consolidate the remaining inner leaves
       int innerCount = filterStack.size() - startFilterSize;

       // 1) If a pending AND exists at the boundary, finalize it first.
       if (!andFilters.isEmpty()) {
          if (innerCount <= 0) {
             throw new IllegalStateException("AND composition has no right-hand filter in inner scope");
          }
          andFilters.add(filterStack.pop()); // left-hand
          Filter andCombined = Filters.and(andFilters.toArray(Filter[]::new));

          if (!orFilters.isEmpty()) {
             orFilters.add(andCombined);
             filterStack.push(Filters.or(orFilters.toArray(Filter[]::new)));
          } else {
             filterStack.push(andCombined);
          }
          return;
       }

       // 2) If we have collected OR parts, fold the remaining inner-scope left side into OR.
       if (!orFilters.isEmpty()) {
          Filter leftCombined = null;
          if (innerCount > 1) {
             List<Filter> leftList = new ArrayList<>();
             for (int i = 0; i < innerCount; i++) {
                leftList.add(0, filterStack.pop()); // preserve order
             }
             leftCombined = Filters.and(leftList.toArray(Filter[]::new));
          } else if (innerCount == 1) {
             leftCombined = filterStack.pop();
          }
          if (leftCombined != null) {
             // The left side should be first logically, but order doesn't affect semantics
             orFilters.add(leftCombined);
          }
          filterStack.push(Filters.or(orFilters.toArray(Filter[]::new)));
          return;
       }

       // 3) No pending AND/OR at all; consolidate remaining inner leaves.
       if (innerCount == 1) {
          // Single inner filter already on stack.
          return;
       } else if (innerCount > 1) {
          List<Filter> tmp = new ArrayList<>();
          for (int i = 0; i < innerCount; i++) {
             tmp.add(0, filterStack.pop()); // preserve order
          }
          filterStack.push(Filters.and(tmp.toArray(Filter[]::new)));
       } else {
          // innerCount == 0 — do not steal from outer scope; let caller decide what to do
          // (exitElemMatchExpr will detect and throw a precise error message)
       }
    }

    @Override
    public void enterExistsExpr(BIAPIQueryParser.ExistsExprContext ctx) {
        if (Log.isDebugEnabled()) {
            Log.debug("enterExists:" + ctx.field.getText() + ctx.op.getText());
        }
        Filter f = Filters.exists(ctx.field.getText());
        filterStack.push(f);
    }


    @Override
    public void enterExprGroup(BIAPIQueryParser.ExprGroupContext ctx) {
        opTypeStack.push(ctx.lp.getType());
    }

    @Override
    public void exitExprGroup(BIAPIQueryParser.ExprGroupContext ctx) {
        buildComposite();
    }


    @Override
    public void enterExprOp(BIAPIQueryParser.ExprOpContext ctx) {
        opTypeStack.push(ctx.op.getType());
    }

    @Override
    public void enterBooleanExpr(BIAPIQueryParser.BooleanExprContext ctx) {
        boolean value;
        if (ctx.value.getText().equalsIgnoreCase("TRUE")) {
            value = true;
        } else if (ctx.value.getText().equalsIgnoreCase("FALSE")) {
            value = false;
        } else {
            throw new IllegalArgumentException("Boolean value not recognized:" + ctx.value.getText());
        }

        if (ctx.op.getType() == BIAPIQueryParser.EQ) {
            filterStack.push(Filters.eq(ctx.field.getText(), value));
        } else if (ctx.op.getType() == BIAPIQueryParser.NEQ) {
            filterStack.push(Filters.ne(ctx.field.getText(), value));
        } else {
            throw new IllegalArgumentException("Operator not recognized:" + ctx.op.getText());
        }
    }

   @Override
   public void enterInExpr(BIAPIQueryParser.InExprContext ctx) {
      String field = ctx.field.getText();

      List<Object> values = new ArrayList<>();
      List<String> unresolvedVars = new ArrayList<>();

      // Handle the special single-variable form: [${ids}]
      BIAPIQueryParser.ValueListExprContext list = ctx.value;
      boolean singleVarOnly = false;
      if (list != null && list.children != null) {
         long varCount = list.children.stream()
                            .filter(c -> c instanceof org.antlr.v4.runtime.tree.TerminalNode tn &&
                                            tn.getSymbol().getType() == BIAPIQueryParser.VARIABLE)
                            .count();
         long valueCount = list.children.stream()
                              .filter(c -> c instanceof org.antlr.v4.runtime.tree.TerminalNode tn &&
                                              switch (tn.getSymbol().getType()) {
                                                 case BIAPIQueryParser.STRING,
                                                      BIAPIQueryParser.QUOTED_STRING,
                                                      BIAPIQueryParser.VARIABLE,
                                                      BIAPIQueryParser.OID,
                                                      BIAPIQueryParser.REFERENCE -> true;
                                                 default -> false;
                                              })
                              .count();
         singleVarOnly = (varCount == 1 && valueCount == 1);
      }

      if (singleVarOnly && sub != null) {
         // Expand the single variable into values
         org.antlr.v4.runtime.tree.TerminalNode varNode = list.children.stream()
                                                             .filter(c -> c instanceof org.antlr.v4.runtime.tree.TerminalNode tn &&
                                                                             tn.getSymbol().getType() == BIAPIQueryParser.VARIABLE)
                                                             .map(c -> (org.antlr.v4.runtime.tree.TerminalNode) c)
                                                             .findFirst().orElse(null);
         String varTokenText = varNode != null ? varNode.getText() : null; // like "${ids}"
         String varName = varTokenText != null ? varTokenText.substring(2, varTokenText.length() - 1) : null;

         Object v = (varName != null && objectVars != null) ? objectVars.get(varName) : null;
         if (v instanceof Collection<?> coll) {
            if (coll.isEmpty()) {
               // Option 3: Warn when resolver returns empty collection - this will result in $in matching nothing
               Log.warnf("Resolver variable '%s' returned empty collection for field '%s'. " +
                        "The resulting $in filter will match no documents. " +
                        "Verify the resolver's supports() method returns true for the current context.",
                        varName, field);
            }
            for (Object item : coll) {
               values.add(coerceValue(item));
            }
         } else if (v != null && v.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(v);
            if (len == 0) {
               // Option 3: Warn when resolver returns empty array
               Log.warnf("Resolver variable '%s' returned empty array for field '%s'. " +
                        "The resulting $in filter will match no documents. " +
                        "Verify the resolver's supports() method returns true for the current context.",
                        varName, field);
            }
            for (int i = 0; i < len; i++) {
               values.add(coerceValue(java.lang.reflect.Array.get(v, i)));
            }
         } else {
            String substituted = sub.replace(varTokenText); // may yield comma-separated scalars
            // Option 2: Check if variable was not resolved (still contains ${...} pattern)
            if (substituted != null && substituted.contains("${")) {
               unresolvedVars.add(varTokenText);
               Log.errorf("Unresolved variable '%s' in IN clause for field '%s'. " +
                        "This likely means the AccessListResolver's supports() method returned false for the current context. " +
                        "The resolver must return true for all contexts where this variable is used in rule filters. " +
                        "Check that your resolver's supports() method matches the area/functionalDomain/action of the request.",
                        varTokenText, field);
               // Don't add unresolved variable as literal - it would never match any document
            } else if (substituted != null && !substituted.isBlank()) {
               for (String part : substituted.split(",")) {
                  values.add(coerceValue(part.trim()));
               }
            }
         }
      } else {
         // Type-aware collection of each literal in the list
         for (var child : list.children) {
            if (!(child instanceof org.antlr.v4.runtime.tree.TerminalNode tn)) continue;
            int t = tn.getSymbol().getType();
            switch (t) {
               case BIAPIQueryParser.QUOTED_STRING: {
                  // Preserve as string regardless of numeric-looking content
                  values.add(coerceValue(new StringLiteral(tn.getText())));
                  break;
               }
               case BIAPIQueryParser.STRING: {
                  // Allow coercion (numbers, booleans, dates, ObjectId, etc.)
                  values.add(coerceValue(tn.getText()));
                  break;
               }
               case BIAPIQueryParser.OID: {
                  values.add(new ObjectId(tn.getText()));
                  break;
               }
               case BIAPIQueryParser.REFERENCE: {
                  values.add(buildReference(field, tn.getText()));
                  break;
               }
               case BIAPIQueryParser.VARIABLE: {
                  String replaced = (sub != null) ? sub.replace(tn.getText()) : tn.getText();
                  // Option 2: Check if variable was not resolved
                  // Only check when we have a substitutor - if no substitutor, variables are expected to remain unresolved
                  if (sub != null && replaced != null && replaced.contains("${")) {
                     unresolvedVars.add(tn.getText());
                     Log.errorf("Unresolved variable '%s' in IN clause for field '%s'. " +
                              "This likely means the AccessListResolver's supports() method returned false for the current context. " +
                              "The resolver must return true for all contexts where this variable is used in rule filters.",
                              tn.getText(), field);
                     // Don't add unresolved variable as literal
                  } else if (replaced != null && !replaced.isBlank()) {
                     // Note: this branch treats variable expansion as scalar(s) to be coerced
                     // If callers need exact string semantics, they should quote the variable in the query: ["${var}"]
                     for (String part : replaced.split(",")) {
                        values.add(coerceValue(part.trim()));
                     }
                  }
                  break;
               }
               default:
                  // Ignore commas and brackets; any other token types are unexpected
                  break;
            }
         }
      }

      // Option 2 & 3: Throw exception if we have unresolved variables - fail fast rather than silently returning no results
      if (!unresolvedVars.isEmpty()) {
         throw new IllegalStateException(
            "Unresolved resolver variable(s) " + unresolvedVars + " in IN clause for field '" + field + "'. " +
            "The AccessListResolver's supports() method must return true for the current request context. " +
            "Ensure your resolver supports all area/functionalDomain/action combinations where this variable is used.");
      }

      Filter f;
      String opText = ctx.op.getText();
      if (":^".equals(opText)) {
         f = Filters.in(field, values);
      } else if (":!^".equals(opText)) {
         f = Filters.nin(field, values);
      } else {
         throw new IllegalArgumentException("Operator not recognized: " + ctx.op.getText());
      }
      filterStack.push(f);
   }

    @Override
    public void exitNotExpr(BIAPIQueryParser.NotExprContext ctx) {
        if (filterStack.isEmpty()) {
            throw new IllegalStateException("NOT expression found but filter stack is empty");
        }
        Filter inner = filterStack.pop();
        // Using $nor to negate the inner expression (works for general expressions)
        Filter negated = Filters.nor(inner);
        filterStack.push(negated);
    }

    private Object coerceValue(Object v) {
        if (v == null) return null;
        // Respect explicit StringLiteral wrapper to force raw string semantics
        if (v instanceof StringLiteral sl) return sl.value();
        if (v instanceof ObjectId) return v;
        if (v instanceof Number || v instanceof Boolean || v instanceof Date) return v;
        if (v instanceof CharSequence s) {
            String str = s.toString();
            if (str.matches("^[a-fA-F0-9]{24}$")) {
                try { return new ObjectId(str); } catch (Exception ignored) {}
            }
            if ("true".equalsIgnoreCase(str) || "false".equalsIgnoreCase(str)) {
                return Boolean.parseBoolean(str);
            }
            if (str.matches("^-?\\d+$")) {
                try { return Long.parseLong(str); } catch (NumberFormatException ignored) {}
            }
            if (str.matches("^-?\\d+\\.\\d+$")) {
                try { return Double.parseDouble(str); } catch (NumberFormatException ignored) {}
            }
            try { return Date.from(ZonedDateTime.parse(str).toInstant()); } catch (Exception ignored) {}
            try { return LocalDate.parse(str); } catch (Exception ignored) {}
            return str;
        }
        return v;
    }

    @Override
    public void enterElemMatchExpr(BIAPIQueryParser.ElemMatchExprContext ctx) {
        // Mark current stack depths so we can scope the nested expression processing
        if (Log.isDebugEnabled()) {
            Log.debug("enterElemMatch: field=" + ctx.field.getText() + ", opDepth=" + opTypeStack.size() + ", filterDepth=" + filterStack.size());
        }
        opTypeMarkers.push(opTypeStack.size());
        filterStackMarkers.push(filterStack.size());
    }

    @Override
    public void exitElemMatchExpr(BIAPIQueryParser.ElemMatchExprContext ctx) {
        // Build composite for nested part only, then wrap it in elemMatch
        int startOp = opTypeMarkers.pop();
        int startFilter = filterStackMarkers.pop();
        if (Log.isDebugEnabled()) {
            Log.debug("exitElemMatch: field=" + ctx.field.getText() + ", startOp=" + startOp + ", startFilter=" + startFilter + ", opDepthBeforeBuild=" + opTypeStack.size() + ", filterDepthBeforeBuild=" + filterStack.size());
        }
        // compose inner filters added within elemMatch
        buildCompositeSince(startOp, startFilter);
        if (Log.isDebugEnabled()) {
            Log.debug("exitElemMatch: after build, filterDepth=" + filterStack.size());
        }
        if (filterStack.size() <= startFilter) {
            throw new IllegalStateException("elemMatch inner expression produced no filters");
        }
        Filter inner = filterStack.pop();
        Filter f = Filters.elemMatch(ctx.field.getText(), inner);
        filterStack.push(f);
        if (Log.isDebugEnabled()) {
            Log.debug("exitElemMatch: pushed elemMatch, filterDepthNow=" + filterStack.size());
        }
    }

    @Override
    public void enterStringExpr(BIAPIQueryParser.StringExprContext ctx) {
        Filter f = makeBasicFilter(ctx.field, ctx.op, ctx.value);
        if (f != null) {
            filterStack.push(f);
        } else {
            Log.warn("makeBasicFilter returned null ... should not happen?");
        }
    }

    protected Filter makeBasicFilter(Token field, Token op, Object value) {
        Filter filter = null;

        if (variableMap != null && value instanceof CommonToken) {
            CommonToken ct = (CommonToken) value;
            // Do not pre-substitute VARIABLE here; let the VARIABLE branch handle substitution + coercion
            if (ct.getType() != BIAPIQueryParser.VARIABLE) {
                value = sub.replace(ct.getText());
            }
        }

        if (value instanceof CommonToken tok) {
            switch (tok.getType()) {
                case BIAPIQueryParser.STRING:
                    value = tok.getText();
                    break;
                case BIAPIQueryParser.QUOTED_STRING:
                    value = tok.getText();
                    break;
                case BIAPIQueryParser.OID:
                    value = new ObjectId(tok.getText());
                    break;
                case BIAPIQueryParser.VARIABLE: {
                    String replaced = (sub != null) ? sub.replace(tok.getText()) : tok.getText();
                    // Check if variable was not resolved (still contains ${...} pattern)
                    // Only check when we have a substitutor - if no substitutor, variables are expected to remain unresolved
                    if (sub != null && replaced != null && replaced.contains("${")) {
                        Log.errorf("Unresolved variable '%s' for field '%s'. " +
                                "This likely means the AccessListResolver's supports() method returned false for the current context, " +
                                "or the variable is not provided by any resolver. " +
                                "Ensure your resolver supports all area/functionalDomain/action combinations where this variable is used.",
                                tok.getText(), field.getText());
                        throw new IllegalStateException(
                            "Unresolved resolver variable '" + tok.getText() + "' for field '" + field.getText() + "'. " +
                            "The AccessListResolver's supports() method must return true for the current request context.");
                    }
                    value = coerceValue(replaced);
                }
                    break;
                case BIAPIQueryParser.NUMBER: {
                    String num = tok.getText();
                    value = Double.parseDouble(num);
                }
                break;
                case BIAPIQueryParser.WHOLENUMBER: {
                    String num = tok.getText();
                    value = Long.parseLong(num);
                }
                break;
                case BIAPIQueryParser.DATE:{
                    String dateString = tok.getText();
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
                    LocalDate date;
                    try {
                        date = LocalDate.parse(dateString, formatter);
                    } catch (DateTimeParseException e) {
                        throw new IllegalArgumentException("Unable to parse date: " + dateString, e);
                    }
                    value = date;
                }
                break;
                case BIAPIQueryParser.DATETIME: {
                    String dateTimeString = tok.getText();
                    DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;
                    ZonedDateTime zonedDateTime;
                    try {
                        zonedDateTime = ZonedDateTime.parse(dateTimeString, formatter);
                    } catch (DateTimeParseException e) {
                        throw new IllegalArgumentException("Unable to parse datetime: " + dateTimeString, e);
                    }
                    Date date = Date.from(zonedDateTime.toInstant());
                    value = date;
                }
                break;
                default:
                    throw new IllegalArgumentException("token:" + tok.getText() + " did not resolve to a known type");
            }
        }

        switch (op.getType()) {
            case BIAPIQueryParser.EQ:
                filter = Filters.eq(field.getText(), value);
                break;
            case BIAPIQueryParser.NEQ:
                filter = Filters.ne(field.getText(), value);
                break;
            case BIAPIQueryParser.GT:
                filter = Filters.gt(field.getText(), value);
                break;
            case BIAPIQueryParser.GTE:
                filter = Filters.gte(field.getText(), value);
                break;
            case BIAPIQueryParser.LT:
                filter = Filters.lt(field.getText(), value);
                break;
            case BIAPIQueryParser.LTE:
                filter = Filters.lte(field.getText(), value);
                break;
            default:
                throw new IllegalArgumentException("Operator invalid in this context:" + op.getText());
        }

        return filter;

    }


    @Override
    public void enterQuotedExpr(BIAPIQueryParser.QuotedExprContext ctx) {
        Filter f = makeBasicFilter(ctx.field, ctx.op, ctx.value);
        filterStack.push(f);
    }

    @Override
    public void enterNumberExpr(BIAPIQueryParser.NumberExprContext ctx) {
        Filter f = makeBasicFilter(ctx.field, ctx.op, ctx.value);
        filterStack.push(f);
    }

    @Override
    public void enterWholenumberExpr(BIAPIQueryParser.WholenumberExprContext ctx) {
        Filter f = makeBasicFilter(ctx.field, ctx.op, ctx.value);
        filterStack.push(f);
    }

    @Override
    public void enterDateTimeExpr(BIAPIQueryParser.DateTimeExprContext ctx) {
        Filter f = makeBasicFilter(ctx.field, ctx.op, ctx.value);
        filterStack.push(f);
    }

    @Override
    public void enterDateExpr(BIAPIQueryParser.DateExprContext ctx) {
        Filter f = makeBasicFilter(ctx.field, ctx.op, ctx.value);
        filterStack.push(f);
    }

    @Override
    public void enterCompoundExpr(BIAPIQueryParser.CompoundExprContext ctx) {
        super.enterCompoundExpr(ctx);
    }

    // Ontology function: hasEdge(predicate, dst)
    // This method relies on the extended grammar; when ontology is not wired, it fails closed (no results)
    @Override
    public void enterHasEdgeExpr(BIAPIQueryParser.HasEdgeExprContext ctx) {
        String predicate = ctx.predicate.getText();
        String dst = ctx.dst.getText();
        if (sub != null) {
            predicate = sub.replace(predicate);
            dst = sub.replace(dst);
        }
        String tenantId = null;
        if (variableMap != null) {
            tenantId = variableMap.get("pTenantId");
            if (tenantId == null) tenantId = variableMap.get("tenantId");
        }

        // Try to canonicalize predicate using OntologyAliasResolver if available via CDI
        try {
            var cdi = jakarta.enterprise.inject.spi.CDI.current();
            if (cdi != null) {
                Class<?> aliasCls = Class.forName("com.e2eq.ontology.core.OntologyAliasResolver");
                var aliasSel = cdi.select(aliasCls);
                Object resolver = aliasSel.isUnsatisfied() ? null : aliasSel.get();
                if (resolver != null) {
                    java.lang.reflect.Method cm = aliasCls.getMethod("canonical", String.class);
                    Object can = cm.invoke(resolver, predicate);
                    if (can instanceof String s) predicate = s;
                }
            }
        } catch (Throwable ignored) { /* continue if resolver not present */ }

        // Optional safety: validate that the predicate's domain matches the current model class (or its subclasses)
        try {
            if (modelClass != null) {
                var cdi = jakarta.enterprise.inject.spi.CDI.current();
                if (cdi != null) {
                    Class<?> regIface = Class.forName("com.e2eq.ontology.core.OntologyRegistry");
                    var regSel = cdi.select(regIface);
                    Object registry = regSel.isUnsatisfied() ? null : regSel.get();
                    if (registry != null) {
                        if (!isPredicateApplicableToModel(registry, regIface, predicate, modelClass)) {
                            // Fail closed: predicate not applicable to this collection/entity
                            filterStack.push(Filters.eq("_id", "__none__"));
                            return;
                        }
                    }
                }
            }
        } catch (Throwable ignored) { /* if ontology not wired, continue with best-effort behavior */ }

        Set<String> ids = Collections.emptySet();
        if (tenantId != null && !tenantId.isBlank()) {
            try {
                var cdi = jakarta.enterprise.inject.spi.CDI.current();
                if (cdi != null) {
                    Class<?> edgeIface = Class.forName("com.e2eq.ontology.repo.OntologyEdgeRepo");
                    var sel = cdi.select(edgeIface);
                    Object store = sel.isUnsatisfied() ? null : sel.get();
                    if (store != null) {
                        java.lang.reflect.Method m = edgeIface.getMethod("srcIdsByDst", String.class, String.class, String.class);
                        Object result = m.invoke(store, tenantId, predicate, dst);
                        if (result instanceof java.util.Set) {
                            ids = (Set<String>) result;
                        }
                    }
                }
            } catch (Throwable t) {
                // ignore and fail closed below
            }
        }
        if (ids == null || ids.isEmpty()) {
            filterStack.push(Filters.eq("_id", "__none__"));
        } else {
            filterStack.push(Filters.in("_id", ids));
        }
    }

    // Reflective helper: check if predicate domain is compatible with modelClass
    private boolean isPredicateApplicableToModel(Object registry, Class<?> regIface, String predicate, Class<?> modelClass) {
        try {
            java.lang.reflect.Method propertyOf = regIface.getMethod("propertyOf", String.class);
            Object optProp = propertyOf.invoke(registry, predicate);
            if (!(optProp instanceof java.util.Optional<?> opt) || opt.isEmpty()) return true; // no info => allow
            Object propDef = opt.get();
            java.lang.reflect.Method domainMethod = propDef.getClass().getMethod("domain");
            Object optDomain = domainMethod.invoke(propDef);
            if (!(optDomain instanceof java.util.Optional<?> od) || od.isEmpty()) return true; // no domain declared
            Object domainIdObj = od.get();
            if (!(domainIdObj instanceof String domainId)) return true;

            String modelId = classIdOf(modelClass);
            if (modelId.equals(domainId)) return true;

            // Traverse parents of modelId to see if it is a subclass of domainId
            return isA(registry, regIface, modelId, domainId);
        } catch (Throwable t) {
            return true; // be permissive on reflection failures
        }
    }

    private boolean isA(Object registry, Class<?> regIface, String typeId, String targetSuperId) {
        if (typeId == null || targetSuperId == null) return false;
        if (typeId.equals(targetSuperId)) return true;
        try {
            java.lang.reflect.Method classOf = regIface.getMethod("classOf", String.class);
            java.util.Set<String> visited = new java.util.HashSet<>();
            String current = typeId;
            while (current != null && visited.add(current)) {
                Object optCls = classOf.invoke(registry, current);
                if (optCls instanceof java.util.Optional<?> opt && opt.isPresent()) {
                    Object classDef = opt.get();
                    java.lang.reflect.Method parentsMethod = classDef.getClass().getMethod("parents");
                    Object parentsObj = parentsMethod.invoke(classDef);
                    if (parentsObj instanceof java.util.Set<?> parents) {
                        for (Object p : parents) {
                            if (p instanceof String ps) {
                                if (ps.equals(targetSuperId)) return true;
                                // BFS upwards
                                if (!visited.contains(ps)) {
                                    // Recurse upward
                                    if (isA(registry, regIface, ps, targetSuperId)) return true;
                                }
                            }
                        }
                        return false;
                    }
                }
                // not found in registry
                return false;
            }
            return false;
        } catch (Throwable t) {
            return true; // on failure, do not block query
        }
    }

    private String classIdOf(Class<?> clazz) {
        try {
            Class<?> annoClass = Class.forName("com.e2eq.ontology.annotations.OntologyClass");
            java.lang.annotation.Annotation a = clazz.getAnnotation((Class<java.lang.annotation.Annotation>) annoClass);
            if (a != null) {
                java.lang.reflect.Method idMethod = annoClass.getMethod("id");
                Object id = idMethod.invoke(a);
                if (id instanceof String s && !s.isEmpty()) return s;
            }
        } catch (Throwable ignored) { }
        return clazz.getSimpleName();
    }
}
