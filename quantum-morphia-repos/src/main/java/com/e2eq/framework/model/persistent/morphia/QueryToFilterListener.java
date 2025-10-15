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
    protected int queryDepth = 0;
    protected Stack<Integer> queryOpTypeMarkers = new Stack<>();
    protected Stack<Integer> queryFilterMarkers = new Stack<>();

    protected boolean complete = false;

    Map<String, String> variableMap = null;
    StringSubstitutor sub = null;

    Class<? extends UnversionedBaseModel> modelClass = null;


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


    @Override
    public void enterQuery(BIAPIQueryParser.QueryContext ctx) {
        if (queryDepth == 0) {
            complete = false;     // only for the top-level query
        }
        queryDepth++;

        // mark the CURRENT stack sizes so we can compose only this query's subtree
        queryOpTypeMarkers.push(opTypeStack.size());
        queryFilterMarkers.push(filterStack.size());
    }

    @Override
    public void exitQuery(BIAPIQueryParser.QueryContext ctx) {
        // compose only what was added during THIS query()
        int startOp = queryOpTypeMarkers.pop();
        int startFilter = queryFilterMarkers.pop();

        // clamp (defensive)
        if (startOp > opTypeStack.size()) startOp = opTypeStack.size();
        if (startFilter > filterStack.size()) startFilter = filterStack.size();

        buildCompositeSince(startOp, startFilter);

        queryDepth--;
        if (queryDepth == 0) {
            // only at the very end of the top-level query
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
            Log.debugf("-- Additional Filters based upon rules:%d--", filterStack.size());
            for (Filter f : filterStack) {
                Log.debugf("    Name:%s Field:%s Value:%s", f.getName(), f.getField(), (f.getValue() == null) ? "NULL" : f.getValue().toString());
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
        // Clamp against current sizes; earlier compositions may have shrunk the stacks
        if (startOpSize > opTypeStack.size()) startOpSize = opTypeStack.size();
        if (startFilterSize > filterStack.size()) startFilterSize = filterStack.size();

        List<Filter> andFilters = new ArrayList<>();
        List<Filter> orFilters = new ArrayList<>();
        final Filter[] filterArray = new Filter[0];

        // Only pop filters that were pushed after startFilterSize
        int finalStartFilterSize = startFilterSize;
        java.util.function.Supplier<Filter> popNewSince = () -> {
            if (filterStack.size() <= finalStartFilterSize) {
                throw new IllegalStateException("No filters added since marker to pop");
            }
            return filterStack.pop();
        };

        // Unwind only operators added since the marker
        while (opTypeStack.size() > startOpSize) {
            int opType = opTypeStack.pop();
            switch (opType) {
                case BIAPIQueryParser.AND: {
                    andFilters.add(popNewSince.get());
                    break;
                }
                case BIAPIQueryParser.OR: {
                    if (!andFilters.isEmpty()) {
                        andFilters.add(popNewSince.get());
                        orFilters.add(Filters.and(andFilters.toArray(filterArray)));
                        andFilters = new ArrayList<>();
                    } else {
                        orFilters.add(popNewSince.get());
                    }
                    break;
                }
                case BIAPIQueryParser.LPAREN: {
                    // close the group for the subsection
                    if (andFilters.isEmpty()) {
                        andFilters.add(popNewSince.get());
                        orFilters.add(Filters.and(andFilters.toArray(filterArray)));
                        andFilters = new ArrayList<>();
                    } else {
                        orFilters.add(popNewSince.get());
                    }

                    // push the grouped composite back
                    if (orFilters.size() == 1) {
                        filterStack.push(orFilters.get(0));
                    } else if (orFilters.size() > 1) {
                        filterStack.push(Filters.or(orFilters.toArray(filterArray)));
                    } else {
                        throw new IllegalStateException("OrCriteria is empty inside LPAREN group");
                    }

                    // Reset for next segment within this scoped build
                    orFilters = new ArrayList<>();
                    break;
                }
                default:
                    throw new IllegalArgumentException("Unsupported operation:" + opType);
            }
        }

        // Finish whatever remains since the marker
        if (!andFilters.isEmpty()) {
            andFilters.add(popNewSince.get());
            orFilters.add(Filters.and(andFilters.toArray(filterArray)));
        } else {
            if (filterStack.size() > startFilterSize) {
                orFilters.add(popNewSince.get());
            } else {
                Log.warn("No new filters since marker to compose");
            }
        }

        if (!orFilters.isEmpty()) {
            if (orFilters.size() == 1) {
                filterStack.push(orFilters.get(0));
            } else {
                filterStack.push(Filters.or(orFilters.toArray(filterArray)));
            }
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
        // IMPORTANT: Do not compose here.
        // Grouping is finalized by buildCompositeSince(...) when unwinding.
        // (We already pushed LPAREN in enterExprGroup.)
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

    private static boolean isQuoted(String s) {
        return s.length() >= 2 &&
                ((s.startsWith("\"") && s.endsWith("\"")) ||
                        (s.startsWith("'") && s.endsWith("'")));
    }

    private static String unquote(String s) {
        return isQuoted(s) ? s.substring(1, s.length() - 1) : s;
    }

    @Override
    public void enterInExpr(BIAPIQueryParser.InExprContext ctx) {
        String field = ctx.field.getText();

        String inner = ctx.value.getText(); // "[...]" or "[${ids}]"
        String innerNoBrackets = inner.substring(1, inner.length() - 1);

        String trimmed = innerNoBrackets.trim();
        boolean isSingleVar = trimmed.startsWith("${") && trimmed.endsWith("}");

        List<Object> values = new ArrayList<>();

        if (isSingleVar && sub != null) {
            String varName = trimmed.substring(2, trimmed.length() - 1);
            Object v = objectVars.get(varName);

            if (v instanceof Collection<?> coll) {
                for (Object item : coll) {
                    if (item instanceof CharSequence s && isQuoted(s.toString())) {
                        values.add(unquote(s.toString())); // ← keep as String
                    } else {
                        values.add(coerceValue(field, item));      // ← normal coercion
                    }
                }
            } else if (v != null && v.getClass().isArray()) {
                int len = java.lang.reflect.Array.getLength(v);
                for (int i = 0; i < len; i++) {
                    Object item = java.lang.reflect.Array.get(v, i);
                    if (item instanceof CharSequence s && isQuoted(s.toString())) {
                        values.add(unquote(s.toString()));
                    } else {
                        values.add(coerceValue(field, item));
                    }
                }
            } else {
                String substituted = sub.replace(trimmed);
                if (substituted != null && !substituted.isBlank()) {
                    for (String raw : substituted.split(",")) {
                        String token = raw.trim();
                        if (isQuoted(token)) {
                            values.add(unquote(token));     // ← keep as String
                        } else {
                            values.add(coerceValue(field, token)); // ← allow numeric/bool/date/OID
                        }
                    }
                }
            }
        } else {
            String substituted = (sub != null) ? sub.replace(innerNoBrackets) : innerNoBrackets;
            if (substituted != null && !substituted.isBlank()) {
                for (String raw : substituted.split(",")) {
                    String token = raw.trim();
                    if (isQuoted(token)) {
                        values.add(unquote(token));         // ← keep as String
                    } else {
                        values.add(coerceValue(field, token));     // ← allow coercion
                    }
                }
            }
        }
        Filter f = switch (ctx.op.getText()) {
            case ":^" -> Filters.in(field, values);
            case ":!^" -> Filters.nin(field, values);
            default -> throw new IllegalArgumentException("Operator not recognized: " + ctx.op.getText());
        };
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

    // Add a field-aware overload
    private Object coerceValue(String field, Object v) {
        if (v == null) return null;
        // Force refName to string
        if ("refName".equals(field)) {
            return (v instanceof CharSequence cs) ? cs.toString() : String.valueOf(v);
        }
        return coerceValueAuto(v); // your existing coerceValue(...) renamed
    }

    private Object coerceValueAuto(Object v) {
        if (v == null) return null;
        // Respect explicit StringLiteral wrapper to force raw string semantics
        if (v instanceof StringLiteral sl) return sl.value();
        if (v instanceof ObjectId) return v;
        if (v instanceof Number || v instanceof Boolean || v instanceof Date) return v;
        if (v instanceof CharSequence s) {
            String str = s.toString();
            if (str.matches("^[a-fA-F0-9]{24}$")) {
                try {
                    return new ObjectId(str);
                } catch (Exception ignored) {
                }
            }
            if ("true".equalsIgnoreCase(str) || "false".equalsIgnoreCase(str)) {
                return Boolean.parseBoolean(str);
            }
            if (str.matches("^-?\\d+$")) {
                try {
                    return Long.parseLong(str);
                } catch (NumberFormatException ignored) {
                }
            }
            if (str.matches("^-?\\d+\\.\\d+$")) {
                try {
                    return Double.parseDouble(str);
                } catch (NumberFormatException ignored) {
                }
            }
            try {
                return Date.from(ZonedDateTime.parse(str).toInstant());
            } catch (Exception ignored) {
            }
            try {
                return LocalDate.parse(str);
            } catch (Exception ignored) {
            }
            return str;
        }
        return v;
    }

    @Override
    public void enterElemMatchExpr(BIAPIQueryParser.ElemMatchExprContext ctx) {
        // remember where elemMatch starts so we can detect what the nested query pushed
        filterStackMarkers.push(filterStack.size());
    }

    @Override
    public void exitElemMatchExpr(BIAPIQueryParser.ElemMatchExprContext ctx) {
        int startFilter = filterStackMarkers.pop();

        // how many new filters did the nested query() leave on the stack?
        int newCount = filterStack.size() - startFilter;

        if (newCount <= 0) {
            Log.errorf("elemMatch produced no inner filters. field=%s startFilter=%d stackSize=%d",
                    ctx.field.getText(), startFilter, filterStack.size());
            throw new IllegalStateException("elemMatch inner expression produced no filters");
        }

        Filter inner;
        if (newCount == 1) {
            inner = filterStack.pop();
        } else {
            // multiple leaves but no inner ops? AND them by default
            List<Filter> inners = new ArrayList<>(newCount);
            while (filterStack.size() > startFilter) {
                inners.add(0, filterStack.pop());
            }
            inner = Filters.and(inners.toArray(new Filter[0]));
        }

        filterStack.push(Filters.elemMatch(ctx.field.getText(), inner));
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
                    value = unquote(tok.getText());
                    break;
                case BIAPIQueryParser.OID:
                    value = new ObjectId(tok.getText());
                    break;
                case BIAPIQueryParser.VARIABLE: {
                    String replaced = (sub != null) ? sub.replace(tok.getText()) : tok.getText();
                    value = coerceValue(field.getText(), replaced);
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
                case BIAPIQueryParser.DATE: {
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


}
