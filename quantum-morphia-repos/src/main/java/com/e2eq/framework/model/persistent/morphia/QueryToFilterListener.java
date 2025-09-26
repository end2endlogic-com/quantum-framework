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


    @Override
    public void enterQuery(BIAPIQueryParser.QueryContext ctx) {
        complete = false;
    }

    @Override
    public void exitQuery(BIAPIQueryParser.QueryContext ctx) {
        buildComposite();
        checkDone();
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
        Filter[] filterArray = new Filter[0];

        // Unwind only the operators added since the marker
        while (opTypeStack.size() > startOpSize) {
            int opType = opTypeStack.pop();
            switch (opType) {
                case BIAPIQueryParser.AND:
                    andFilters.add(filterStack.pop());
                    break;
                case BIAPIQueryParser.OR:
                    if (!andFilters.isEmpty()) {
                        andFilters.add(filterStack.pop());
                        orFilters.add(Filters.and(andFilters.toArray(filterArray)));
                        andFilters = new ArrayList<>();
                    } else {
                        orFilters.add(filterStack.pop());
                    }
                    break;
                case BIAPIQueryParser.LPAREN:
                    if (andFilters.isEmpty()) {
                        andFilters.add(filterStack.pop());
                        orFilters.add(Filters.and(andFilters.toArray(filterArray)));
                        andFilters = new ArrayList<>();
                    } else {
                        orFilters.add(filterStack.pop());
                    }
                    if (orFilters.size() == 1) {
                        filterStack.push(orFilters.get(0));
                    } else if (orFilters.size() > 1) {
                        filterStack.push(Filters.or(orFilters.toArray(filterArray)));
                    } else {
                        throw new IllegalStateException("OrCriteria is empty expected to be not empty?");
                    }
                    orFilters = new ArrayList<>();
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported operation:" + opType);

            }
        }
        if (!andFilters.isEmpty()) {
            andFilters.add(filterStack.pop());
            orFilters.add(Filters.and(andFilters.toArray(filterArray)));
        } else {
            if (!filterStack.empty())
                orFilters.add(filterStack.pop());
            else
                Log.warn("!! Filter stack is empty, expected at least one filter, possible problem with grammar");
        }
        if (orFilters.size() == 1) {
            filterStack.push(orFilters.get(0));
        } else if (orFilters.size() > 1) {
            filterStack.push(Filters.or(orFilters.toArray(filterArray)));
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

        String inner = ctx.value.getText(); // like "[a,b]" or "[${ids}]"
        String innerNoBrackets = inner.substring(1, inner.length() - 1);

        String trimmed = innerNoBrackets.trim();
        boolean isSingleVar = trimmed.startsWith("${") && trimmed.endsWith("}");

        List<Object> values = new ArrayList<>();

        if (isSingleVar && sub != null) {
            String varName = trimmed.substring(2, trimmed.length() - 1);
            Object v = objectVars != null ? objectVars.get(varName) : null;
            if (v instanceof Collection<?> coll) {
                for (Object item : coll) {
                    values.add(coerceValue(item));
                }
            } else if (v != null && v.getClass().isArray()) {
                int len = java.lang.reflect.Array.getLength(v);
                for (int i = 0; i < len; i++) {
                    Object item = java.lang.reflect.Array.get(v, i);
                    values.add(coerceValue(item));
                }
            } else {
                String substituted = sub.replace(trimmed);
                if (substituted != null && !substituted.isBlank()) {
                    for (String token : substituted.split(",")) {
                        values.add(coerceValue(token.trim()));
                    }
                }
            }
        } else {
            String substituted = (sub != null) ? sub.replace(innerNoBrackets) : innerNoBrackets;
            if (substituted != null && !substituted.isBlank()) {
                for (String raw : substituted.split(",")) {
                    String token = raw.trim();
                    values.add(coerceValue(token));
                }
            }
        }

        Filter f = Filters.in(field, values);
        filterStack.push(f);
    }

    private Object coerceValue(Object v) {
        if (v == null) return null;
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
        opTypeMarkers.push(opTypeStack.size());
        filterStackMarkers.push(filterStack.size());
    }

    @Override
    public void exitElemMatchExpr(BIAPIQueryParser.ElemMatchExprContext ctx) {
        // Build composite for nested part only, then wrap it in elemMatch
        int startOp = opTypeMarkers.pop();
        int startFilter = filterStackMarkers.pop();
        // compose inner filters added within elemMatch
        buildCompositeSince(startOp, startFilter);

        if (filterStack.size() <= startFilter) {
            throw new IllegalStateException("elemMatch inner expression produced no filters");
        }
        Filter inner = filterStack.pop();
        Filter f = Filters.elemMatch(ctx.field.getText(), inner);
        filterStack.push(f);
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


}
