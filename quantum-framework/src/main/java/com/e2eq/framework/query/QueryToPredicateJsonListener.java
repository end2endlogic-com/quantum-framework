package com.e2eq.framework.query.runtime;

import com.e2eq.framework.grammar.BIAPIQueryBaseListener;
import com.e2eq.framework.grammar.BIAPIQueryParser;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.Token;
import org.apache.commons.text.StringSubstitutor;
import org.bson.types.ObjectId;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * In-memory evaluator for BIAPIQuery that compiles a {@code Predicate<JsonNode>}.
 * This avoids reflection on POJOs and is Quarkus/GraalVM friendly.
 */
public class QueryToPredicateJsonListener extends BIAPIQueryBaseListener {
    private final Map<String, String> variableMap;
    private final Map<String, Object> objectVars;
    private final StringSubstitutor sub;

    private final Deque<Predicate<JsonNode>> predicateStack = new ArrayDeque<>();
    private final Deque<Integer> opTypeStack = new ArrayDeque<>();
    private final Deque<Integer> opTypeMarkers = new ArrayDeque<>();
    private final Deque<Integer> predStackMarkers = new ArrayDeque<>();

    private boolean complete = false;
    private int queryDepth = 0;
    private boolean textClauseSeen = false;

    // Track nesting so text(...) is rejected in positions MongoDB forbids for $text.
    private int notNestingDepth = 0;
    private int elemMatchNestingDepth = 0;
    private int edgeFilterNestingDepth = 0;
    private Class<? extends UnversionedBaseModel> modelClass = null;

    private static final Pattern SPECIAL_REGEX_CHARS = Pattern.compile("[{}()\\[\\].+*?^$\\\\|\\-]");
    /** Split field values into word tokens (letters/digits); approximates MongoDB $text word matching. */
    private static final Pattern WORD_SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");

    /**
     * Creates a listener with no variable substitution. Useful for queries without ${vars} or object variables.
     */
    public QueryToPredicateJsonListener() {
        this(null, null, null, null);
    }

    /**
     * Creates a listener configured with a modelClass.
     */
    public QueryToPredicateJsonListener(Class<? extends UnversionedBaseModel> modelClass) {
        this(null, null, null, modelClass);
    }

    /**
     * Creates a listener that can substitute ${vars} in the query using the provided map.
     * @param variableMap name/value pairs used for StringSubstitutor (${var}) expansion; may be null
     */
    public QueryToPredicateJsonListener(Map<String, String> variableMap) {
        this(variableMap, null, variableMap != null ? new StringSubstitutor(variableMap) : null, null);
    }

    /**
     * Creates a listener with variable substitution and modelClass.
     */
    public QueryToPredicateJsonListener(Map<String, String> variableMap, Class<? extends UnversionedBaseModel> modelClass) {
        this(variableMap, null, variableMap != null ? new StringSubstitutor(variableMap) : null, modelClass);
    }

    /**
     * Full constructor with variable and object variable support.
     * @param variableMap map for ${var} expansion (StringSubstitutor). May be null.
     * @param objectVars object-valued variables (e.g., for IN list expansion). May be null.
     * @param sub optional custom StringSubstitutor to use; if null and variableMap is non-null, a default will be created.
     */
    public QueryToPredicateJsonListener(Map<String, String> variableMap, Map<String, Object> objectVars, StringSubstitutor sub) {
        this(variableMap, objectVars, sub, null);
    }

    /**
     * Full constructor with variable, object variable, and modelClass support.
     */
    public QueryToPredicateJsonListener(Map<String, String> variableMap, Map<String, Object> objectVars, StringSubstitutor sub, Class<? extends UnversionedBaseModel> modelClass) {
        this.variableMap = variableMap;
        this.objectVars = (objectVars == null) ? Collections.emptyMap() : objectVars;
        this.sub = (sub != null) ? sub : new StringSubstitutor(variableMap != null ? variableMap : Collections.emptyMap());
        this.modelClass = modelClass;
    }

    public boolean isInsideEdgeFilter() {
        return edgeFilterNestingDepth > 0;
    }

    /**
     * Returns the compiled predicate after the parse tree has been fully walked.
     * @return the resulting Predicate that evaluates a JsonNode according to the parsed query
     * @throws IllegalStateException if called before the top-level query has finished processing
     */
    public Predicate<JsonNode> getPredicate() {
        if (!complete) throw new IllegalStateException("Predicate is incomplete");
        return predicateStack.peek();
    }

    private void registerTextClause() {
        if (textClauseSeen) {
            throw new IllegalStateException(
                    "Multiple text(...) clauses are not supported in a single query; MongoDB allows only one $text operator per query.");
        }
        // MongoDB requires $text to be a top-level query operator.
        if (notNestingDepth > 0) {
            throw new IllegalStateException(
                    "text(...) cannot be negated with NOT (!!). " +
                    "MongoDB requires $text to be a top-level query operator. " +
                    "Example invalid query: !!text(\"foo\")");
        }
        if (elemMatchNestingDepth > 0) {
            throw new IllegalStateException(
                    "text(...) cannot be used inside an elemMatch expression. " +
                    "MongoDB requires $text to be a top-level query operator. " +
                    "Example invalid query: arrayField:{text(\"foo\")}");
        }
        textClauseSeen = true;
    }

    /**
     * Marker for predicates that include text(...) so OR composition can reject
     * nesting that MongoDB forbids for $text.
     */
    private interface TextBearing {
        boolean containsText();
    }

    private static final class TextBearingPredicate implements Predicate<JsonNode>, TextBearing {
        private final Predicate<JsonNode> delegate;
        private final boolean containsText;

        TextBearingPredicate(Predicate<JsonNode> delegate, boolean containsText) {
            this.delegate = delegate;
            this.containsText = containsText;
        }

        @Override
        public boolean test(JsonNode node) {
            return delegate.test(node);
        }

        @Override
        public boolean containsText() {
            return containsText;
        }
    }

    private static boolean isTextBearing(Predicate<JsonNode> p) {
        return p instanceof TextBearing tb && tb.containsText();
    }

    private static Predicate<JsonNode> wrap(Predicate<JsonNode> p, boolean containsText) {
        if (p instanceof TextBearingPredicate tbp && tbp.containsText == containsText) {
            return tbp;
        }
        return new TextBearingPredicate(p, containsText);
    }

    // ---- Parsing lifecycle ----
    /** {@inheritDoc} */
    @Override public void enterQuery(BIAPIQueryParser.QueryContext ctx) { queryDepth++; complete = false; }
    /** {@inheritDoc} */
    @Override public void exitQuery(BIAPIQueryParser.QueryContext ctx) {
        queryDepth--; if (queryDepth == 0) { buildComposite(); checkDone(); }
    }

    private void checkDone() {
        if (predicateStack.size() != 1) throw new IllegalStateException("Predicate stack not 1 at end; size=" + predicateStack.size());
        complete = true;
    }

    private void buildComposite() { buildCompositeSince(0, 0); }

    private void buildCompositeSince(int startOpSize, int startPredSize) {
        List<Predicate<JsonNode>> ands = new ArrayList<>();
        List<Predicate<JsonNode>> ors = new ArrayList<>();

        while (opTypeStack.size() > startOpSize) {
            int opType = opTypeStack.pop();
            switch (opType) {
                case BIAPIQueryParser.AND -> {
                    if (predicateStack.size() <= startPredSize) throw new IllegalStateException("AND expects RHS predicate in inner scope");
                    ands.add(predicateStack.pop());
                }
                case BIAPIQueryParser.OR -> {
                    if (predicateStack.size() <= startPredSize) throw new IllegalStateException("OR expects RHS predicate in inner scope");
                    if (!ands.isEmpty()) {
                        ands.add(predicateStack.pop());
                        ors.add(allOf(ands));
                        ands = new ArrayList<>();
                    } else {
                        ors.add(predicateStack.pop());
                    }
                }
                case BIAPIQueryParser.LPAREN -> {
                    if (predicateStack.size() <= startPredSize) throw new IllegalStateException("Group close found no inner predicates");
                    if (ands.isEmpty()) {
                        ors.add(predicateStack.pop());
                    } else {
                        ands.add(predicateStack.pop());
                        ors.add(allOf(ands));
                        ands = new ArrayList<>();
                    }
                    if (ors.size() == 1) {
                        predicateStack.push(ors.get(0));
                    } else if (ors.size() > 1) {
                        predicateStack.push(anyOf(ors));
                    } else {
                        throw new IllegalStateException("Or list empty in group");
                    }
                    ors = new ArrayList<>();
                }
                default -> throw new IllegalArgumentException("Unsupported operation:" + opType);
            }
        }

        int innerCount = predicateStack.size() - startPredSize;
        if (!ands.isEmpty()) {
            if (innerCount <= 0) throw new IllegalStateException("AND composition missing LHS in inner scope");
            ands.add(predicateStack.pop());
            Predicate<JsonNode> andCombined = allOf(ands);
            if (!ors.isEmpty()) {
                ors.add(andCombined);
                predicateStack.push(anyOf(ors));
            } else {
                predicateStack.push(andCombined);
            }
            return;
        }

        if (!ors.isEmpty()) {
            Predicate<JsonNode> leftCombined = null;
            if (innerCount > 1) {
                List<Predicate<JsonNode>> leftList = new ArrayList<>();
                for (int i = 0; i < innerCount; i++) leftList.add(0, predicateStack.pop());
                leftCombined = allOf(leftList);
            } else if (innerCount == 1) leftCombined = predicateStack.pop();
            if (leftCombined != null) ors.add(leftCombined);
            predicateStack.push(anyOf(ors));
            return;
        }

        if (innerCount == 1) return; // already on stack
        if (innerCount > 1) {
            List<Predicate<JsonNode>> tmp = new ArrayList<>();
            for (int i = 0; i < innerCount; i++) tmp.add(0, predicateStack.pop());
            predicateStack.push(allOf(tmp));
        }
    }

    private static Predicate<JsonNode> allOf(List<Predicate<JsonNode>> list) {
        boolean text = list.stream().anyMatch(QueryToPredicateJsonListener::isTextBearing);
        return wrap(obj -> list.stream().allMatch(p -> p.test(obj)), text);
    }
    private static Predicate<JsonNode> anyOf(List<Predicate<JsonNode>> list) {
        // MongoDB requires $text to be top-level; it cannot appear inside $or.
        for (Predicate<JsonNode> p : list) {
            if (isTextBearing(p)) {
                throw new IllegalStateException(
                        "text(...) cannot be used inside an OR expression. " +
                        "MongoDB requires $text to be a top-level query operator. " +
                        "Example invalid query: text(\"foo\") || status:OPEN");
            }
        }
        return wrap(obj -> list.stream().anyMatch(p -> p.test(obj)), false);
    }

    // ---- Operators and groups ----
    /** {@inheritDoc} */
    @Override public void enterExprGroup(BIAPIQueryParser.ExprGroupContext ctx) { opTypeStack.push(ctx.lp.getType()); }
    /** {@inheritDoc} */
    @Override public void exitExprGroup(BIAPIQueryParser.ExprGroupContext ctx) { buildComposite(); }
    /** {@inheritDoc} */
    @Override public void enterExprOp(BIAPIQueryParser.ExprOpContext ctx) { opTypeStack.push(ctx.op.getType()); }

    /** {@inheritDoc} */
    @Override public void enterNotExpr(BIAPIQueryParser.NotExprContext ctx) {
        notNestingDepth++;
    }

    /** {@inheritDoc} */
    @Override public void exitNotExpr(BIAPIQueryParser.NotExprContext ctx) {
        notNestingDepth--;
        if (predicateStack.isEmpty()) throw new IllegalStateException("NOT with empty stack");
        Predicate<JsonNode> inner = predicateStack.pop();
        // Negation preserves text-bearing-ness for nested OR validation edge cases.
        predicateStack.push(wrap(inner.negate(), isTextBearing(inner)));
    }

    // ---- Leaf expressions ----
    /** {@inheritDoc} */
    @Override public void enterNullExpr(BIAPIQueryParser.NullExprContext ctx) {
        String field = ctx.field.getText();
        if (ctx.op.getType() == BIAPIQueryParser.EQ) predicateStack.push(node -> getNodeAt(node, field).isNull() || getNodeAt(node, field).isMissingNode());
        else predicateStack.push(node -> !(getNodeAt(node, field).isNull() || getNodeAt(node, field).isMissingNode()));
    }

    /** {@inheritDoc} */
    @Override public void enterExistsExpr(BIAPIQueryParser.ExistsExprContext ctx) {
        String field = ctx.field.getText();
        predicateStack.push(node -> exists(node, field));
    }

    /** {@inheritDoc} */
    @Override public void enterBooleanExpr(BIAPIQueryParser.BooleanExprContext ctx) {
        String field = ctx.field.getText();
        boolean value = ctx.value.getText().equalsIgnoreCase("TRUE");
        if (ctx.op.getType() == BIAPIQueryParser.EQ)
            predicateStack.push(node -> Objects.equals(asBoolean(getNodeAt(node, field)), value));
        else
            predicateStack.push(node -> !Objects.equals(asBoolean(getNodeAt(node, field)), value));
    }

    /** {@inheritDoc} */
    @Override public void enterRegexExpr(BIAPIQueryParser.RegexExprContext ctx) {
        String field = ctx.field.getText();
        String escaped = escapeRegexChars(ctx.regex().value.getText());
        String pattern = (ctx.regex().leftW == null ? "^" : ".*") + escaped + (ctx.regex().rightW == null ? "$" : ".*");
        Pattern compiled = Pattern.compile(pattern, isCaseSensitive(ctx.regex().caseMode()) ? 0 : Pattern.CASE_INSENSITIVE);
        Predicate<JsonNode> p = node -> {
            JsonNode v = getNodeAt(node, field);
            if (v == null || v.isMissingNode() || v.isNull()) return false;
            if (v.isArray()) {
                for (JsonNode el : v) if (compiled.matcher(el.asText()).find()) return true;
                return false;
            }
            return compiled.matcher(v.asText()).find();
        };
        if (ctx.op.getType() == BIAPIQueryParser.EQ) predicateStack.push(p);
        else predicateStack.push(p.negate());
    }

    /** {@inheritDoc} */
    @Override public void enterTextExpr(BIAPIQueryParser.TextExprContext ctx) {
        registerTextClause();
        String raw = resolveTextValue(ctx.value);
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("text(...) search value must be non-empty.");
        }
        List<String> terms = tokenizeSearchTerms(trimmed);
        if (terms.isEmpty()) {
            throw new IllegalArgumentException("text(...) search value must be non-empty.");
        }
        // Tag as text-bearing so OR composition can reject MongoDB-illegal nesting.
        predicateStack.push(wrap(node -> matchesTextSearch(node, terms), true));
    }

    /** {@inheritDoc} */
    @Override public void enterInExpr(BIAPIQueryParser.InExprContext ctx) {
        String field = ctx.field.getText();
        List<Object> values = collectInValues(ctx.value, field);
        // Normalize values into a Set of comparable representations
        Set<Object> set = new HashSet<>(values);
        Predicate<JsonNode> p = node -> {
            JsonNode fv = getNodeAt(node, field);
            if (fv == null || fv.isMissingNode() || fv.isNull()) return false;
            if (fv.isArray()) {
                for (JsonNode el : fv) if (containsMatch(set, coerceFromJsonNode(el))) return true;
                return false;
            }
            return containsMatch(set, coerceFromJsonNode(fv));
        };
        if (ctx.op.getType() == BIAPIQueryParser.IN) predicateStack.push(p); else predicateStack.push(p.negate());
    }

    /** {@inheritDoc} */
    @Override public void enterStringExpr(BIAPIQueryParser.StringExprContext ctx) { predicateStack.push(makeBasicPredicate(ctx.field, ctx.op, ctx.value, isCaseSensitive(ctx.caseMode()))); }
    /** {@inheritDoc} */
    @Override public void enterQuotedExpr(BIAPIQueryParser.QuotedExprContext ctx) { predicateStack.push(makeBasicPredicate(ctx.field, ctx.op, ctx.value, isCaseSensitive(ctx.caseMode()))); }
    /** {@inheritDoc} */
    @Override public void enterNumberExpr(BIAPIQueryParser.NumberExprContext ctx) { predicateStack.push(makeBasicPredicate(ctx.field, ctx.op, ctx.value)); }
    /** {@inheritDoc} */
    @Override public void enterWholenumberExpr(BIAPIQueryParser.WholenumberExprContext ctx) { predicateStack.push(makeBasicPredicate(ctx.field, ctx.op, ctx.value)); }
    /** {@inheritDoc} */
    @Override public void enterDateTimeExpr(BIAPIQueryParser.DateTimeExprContext ctx) { predicateStack.push(makeBasicPredicate(ctx.field, ctx.op, ctx.value)); }
    /** {@inheritDoc} */
    @Override public void enterDateExpr(BIAPIQueryParser.DateExprContext ctx) { predicateStack.push(makeBasicPredicate(ctx.field, ctx.op, ctx.value)); }
    /** {@inheritDoc} */
    @Override public void enterReferenceExpr(BIAPIQueryParser.ReferenceExprContext ctx) { predicateStack.push(makeBasicPredicate(ctx.field, ctx.op, ctx.value)); }

    // ---- elemMatch ----
    /** {@inheritDoc} */
    @Override public void enterElemMatchExpr(BIAPIQueryParser.ElemMatchExprContext ctx) {
        opTypeMarkers.push(opTypeStack.size());
        predStackMarkers.push(predicateStack.size());
        elemMatchNestingDepth++;
    }
    /** {@inheritDoc} */
    @Override public void exitElemMatchExpr(BIAPIQueryParser.ElemMatchExprContext ctx) {
        elemMatchNestingDepth--;
        int startOp = opTypeMarkers.pop();
        int startPred = predStackMarkers.pop();
        buildCompositeSince(startOp, startPred);
        if (predicateStack.size() <= startPred) throw new IllegalStateException("elemMatch produced no inner predicate");
        Predicate<JsonNode> inner = predicateStack.pop();
        String field = ctx.field.getText();
        predicateStack.push(wrap(node -> {
            JsonNode fv = getNodeAt(node, field);
            if (fv != null && fv.isArray()) {
                for (JsonNode el : fv) if (inner.test(el)) return true;
            }
            return false;
        }, isTextBearing(inner)));
    }

    // ---- expand directive ----
    /** {@inheritDoc} */
    @Override
    public void enterExpandExpr(BIAPIQueryParser.ExpandExprContext ctx) {
        predicateStack.push(wrap(node -> true, false));
    }

    // ---- ontology edge expressions ----
    /** {@inheritDoc} */
    @Override
    public void enterHasEdgeExpr(BIAPIQueryParser.HasEdgeExprContext ctx) {
        if (ctx.edgeFilter != null) {
            opTypeMarkers.push(opTypeStack.size());
            predStackMarkers.push(predicateStack.size());
            edgeFilterNestingDepth++;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void exitHasEdgeExpr(BIAPIQueryParser.HasEdgeExprContext ctx) {
        Predicate<JsonNode> edgeFilterPred = null;
        if (ctx.edgeFilter != null) {
            edgeFilterNestingDepth--;
            int startOp = opTypeMarkers.pop();
            int startPred = predStackMarkers.pop();
            buildCompositeSince(startOp, startPred);
            if (predicateStack.size() > startPred) {
                edgeFilterPred = predicateStack.pop();
            }
        }
        processHasEdge(ctx.predicate, ctx.dst, edgeFilterPred, ctx.edgeFilter);
    }

    /** {@inheritDoc} */
    @Override
    public void enterHasOutgoingEdgeExpr(BIAPIQueryParser.HasOutgoingEdgeExprContext ctx) {
        if (ctx.edgeFilter != null) {
            opTypeMarkers.push(opTypeStack.size());
            predStackMarkers.push(predicateStack.size());
            edgeFilterNestingDepth++;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void exitHasOutgoingEdgeExpr(BIAPIQueryParser.HasOutgoingEdgeExprContext ctx) {
        Predicate<JsonNode> edgeFilterPred = null;
        if (ctx.edgeFilter != null) {
            edgeFilterNestingDepth--;
            int startOp = opTypeMarkers.pop();
            int startPred = predStackMarkers.pop();
            buildCompositeSince(startOp, startPred);
            if (predicateStack.size() > startPred) {
                edgeFilterPred = predicateStack.pop();
            }
        }
        processHasEdge(ctx.predicate, ctx.dst, edgeFilterPred, ctx.edgeFilter);
    }

    /** {@inheritDoc} */
    @Override
    public void enterHasIncomingEdgeExpr(BIAPIQueryParser.HasIncomingEdgeExprContext ctx) {
        if (ctx.edgeFilter != null) {
            opTypeMarkers.push(opTypeStack.size());
            predStackMarkers.push(predicateStack.size());
            edgeFilterNestingDepth++;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void exitHasIncomingEdgeExpr(BIAPIQueryParser.HasIncomingEdgeExprContext ctx) {
        Predicate<JsonNode> edgeFilterPred = null;
        if (ctx.edgeFilter != null) {
            edgeFilterNestingDepth--;
            int startOp = opTypeMarkers.pop();
            int startPred = predStackMarkers.pop();
            buildCompositeSince(startOp, startPred);
            if (predicateStack.size() > startPred) {
                edgeFilterPred = predicateStack.pop();
            }
        }
        processHasIncomingEdge(ctx.predicate, ctx.src, edgeFilterPred, ctx.edgeFilter);
    }

    private void processHasEdge(Token predicateToken, Token dstToken, Predicate<JsonNode> edgeFilterPred, BIAPIQueryParser.QueryContext edgeFilterCtx) {
        String predicate = predicateToken.getText();
        String dst = dstToken.getText();
        if (sub != null) {
            predicate = sub.replace(predicate);
            dst = sub.replace(dst);
        }
        predicate = cleanString(predicate);
        dst = cleanString(dst);

        String tenantId = null;
        if (variableMap != null) {
            tenantId = variableMap.get("pTenantId");
            if (tenantId == null) tenantId = variableMap.get("tenantId");
        }

        predicate = canonicalizePredicate(predicate);

        if (modelClass != null && !isPredicateApplicableToModelSafe(predicate, modelClass)) {
            predicateStack.push(wrap(node -> false, false));
            return;
        }

        DataDomain dataDomain = resolveOntologyDataDomain(tenantId);
        Object morphiaFilter = compileEdgeFilterToMorphia(edgeFilterCtx);
        Set<String> ids = ontologySrcIdsByDst(dataDomain, predicate, dst, morphiaFilter);
        pushIdPredicate(ids);
    }

    private void processHasIncomingEdge(Token predicateToken, Token srcToken, Predicate<JsonNode> edgeFilterPred, BIAPIQueryParser.QueryContext edgeFilterCtx) {
        String predicate = predicateToken.getText();
        String src = srcToken.getText();
        if (sub != null) {
            predicate = sub.replace(predicate);
            src = sub.replace(src);
        }
        predicate = cleanString(predicate);
        src = cleanString(src);

        String tenantId = null;
        if (variableMap != null) {
            tenantId = variableMap.get("pTenantId");
            if (tenantId == null) tenantId = variableMap.get("tenantId");
        }

        predicate = canonicalizePredicate(predicate);

        if (modelClass != null && !isPredicateRangeApplicableToModelSafe(predicate, modelClass)) {
            predicateStack.push(wrap(node -> false, false));
            return;
        }

        DataDomain dataDomain = resolveOntologyDataDomain(tenantId);
        Object morphiaFilter = compileEdgeFilterToMorphia(edgeFilterCtx);
        Set<String> ids = ontologyDstIdsBySrc(dataDomain, predicate, src, morphiaFilter);
        pushIdPredicate(ids);
    }

    private String canonicalizePredicate(String predicate) {
        try {
            var cdi = jakarta.enterprise.inject.spi.CDI.current();
            if (cdi != null) {
                Class<?> aliasCls = Class.forName("com.e2eq.ontology.core.OntologyAliasResolver");
                var aliasSel = cdi.select(aliasCls);
                Object resolver = aliasSel.isUnsatisfied() ? null : aliasSel.get();
                if (resolver != null) {
                    java.lang.reflect.Method cm = aliasCls.getMethod("canonical", String.class);
                    Object can = cm.invoke(resolver, predicate);
                    if (can instanceof String s) return s;
                }
            }
        } catch (Throwable ignored) { }
        return predicate;
    }

    private boolean isPredicateApplicableToModelSafe(String predicate, Class<?> modelClass) {
        try {
            var cdi = jakarta.enterprise.inject.spi.CDI.current();
            if (cdi != null) {
                Class<?> regIface = Class.forName("com.e2eq.ontology.core.OntologyRegistry");
                var regSel = cdi.select(regIface);
                Object registry = regSel.isUnsatisfied() ? null : regSel.get();
                if (registry != null) {
                    return isPredicateApplicableToModel(registry, regIface, predicate, modelClass);
                }
            }
        } catch (Throwable ignored) { }
        return true;
    }

    private boolean isPredicateRangeApplicableToModelSafe(String predicate, Class<?> modelClass) {
        try {
            var cdi = jakarta.enterprise.inject.spi.CDI.current();
            if (cdi != null) {
                Class<?> regIface = Class.forName("com.e2eq.ontology.core.OntologyRegistry");
                var regSel = cdi.select(regIface);
                Object registry = regSel.isUnsatisfied() ? null : regSel.get();
                if (registry != null) {
                    return isPredicateRangeApplicableToModel(registry, regIface, predicate, modelClass);
                }
            }
        } catch (Throwable ignored) { }
        return true;
    }

    private boolean isPredicateApplicableToModel(Object registry, Class<?> regIface, String predicate, Class<?> modelClass) {
        try {
            java.lang.reflect.Method propertyOf = regIface.getMethod("propertyOf", String.class);
            Object optProp = propertyOf.invoke(registry, predicate);
            if (!(optProp instanceof java.util.Optional<?> opt) || opt.isEmpty()) return true;
            Object propDef = opt.get();
            java.lang.reflect.Method domainMethod = propDef.getClass().getMethod("domain");
            Object optDomain = domainMethod.invoke(propDef);
            if (!(optDomain instanceof java.util.Optional<?> od) || od.isEmpty()) return true;
            Object domainIdObj = od.get();
            if (!(domainIdObj instanceof String domainId)) return true;

            String modelId = classIdOf(modelClass);
            if (modelId.equals(domainId)) return true;
            return isA(registry, regIface, modelId, domainId);
        } catch (Throwable t) {
            return true;
        }
    }

    private boolean isPredicateRangeApplicableToModel(Object registry, Class<?> regIface, String predicate, Class<?> modelClass) {
        try {
            java.lang.reflect.Method propertyOf = regIface.getMethod("propertyOf", String.class);
            Object optProp = propertyOf.invoke(registry, predicate);
            if (!(optProp instanceof java.util.Optional<?> opt) || opt.isEmpty()) return true;
            Object propDef = opt.get();
            java.lang.reflect.Method rangeMethod = propDef.getClass().getMethod("range");
            Object optRange = rangeMethod.invoke(propDef);
            if (!(optRange instanceof java.util.Optional<?> or) || or.isEmpty()) return true;
            Object rangeIdObj = or.get();
            if (!(rangeIdObj instanceof String rangeId)) return true;

            String modelId = classIdOf(modelClass);
            if (modelId.equals(rangeId)) return true;
            return isA(registry, regIface, modelId, rangeId);
        } catch (Throwable t) {
            return true;
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
                                if (!visited.contains(ps)) {
                                    if (isA(registry, regIface, ps, targetSuperId)) return true;
                                }
                            }
                        }
                        return false;
                    }
                }
                return false;
            }
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    @SuppressWarnings("unchecked")
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

    private DataDomain resolveOntologyDataDomain(String requestedTenantId) {
        try {
            Optional<PrincipalContext> principalContext = SecurityContext.getPrincipalContext();
            if (principalContext.isPresent()) {
                DataDomain principalDataDomain = principalContext.get().getDataDomain();
                if (principalDataDomain != null) {
                    if (requestedTenantId != null && !requestedTenantId.isBlank()
                            && !Objects.equals(requestedTenantId, principalDataDomain.getTenantId())) {
                        throw new SecurityException(
                                "Ontology relationship tenant does not match the authenticated principal DataDomain");
                    }
                    return principalDataDomain;
                }
            }

            String tenantId = firstNonBlank(requestedTenantId, variable("pTenantId"), variable("tenantId"));
            String orgRefName = firstNonBlank(variable("orgRefName"), variable("dcOrgRefName"));
            String accountNum = firstNonBlank(variable("pAccountId"), variable("dcAccountId"));
            String dataSegmentValue = firstNonBlank(variable("pDataSegment"), variable("dcDataSegment"));
            if (tenantId == null || orgRefName == null || accountNum == null || dataSegmentValue == null) {
                return null;
            }

            int dataSegment = Integer.parseInt(dataSegmentValue);
            DataDomain dataDomain = new DataDomain();
            dataDomain.setTenantId(tenantId);
            dataDomain.setOrgRefName(orgRefName);
            dataDomain.setAccountNum(accountNum);
            dataDomain.setDataSegment(dataSegment);
            String ownerId = variable("ownerId");
            if (ownerId != null && !ownerId.isBlank()) {
                dataDomain.setOwnerId(ownerId);
            }
            return dataDomain;
        } catch (SecurityException se) {
            throw se;
        } catch (Throwable t) {
            return null;
        }
    }

    private String variable(String name) {
        return variableMap == null ? null : variableMap.get(name);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private Object compileEdgeFilterToMorphia(BIAPIQueryParser.QueryContext edgeFilterCtx) {
        if (edgeFilterCtx == null) return null;
        try {
            Class<?> qtfClass = Class.forName("com.e2eq.framework.model.persistent.morphia.QueryToFilterListener");
            java.lang.reflect.Constructor<?> ctor;
            Object listener;
            try {
                ctor = qtfClass.getConstructor(Map.class, Map.class, StringSubstitutor.class, Class.class);
                listener = ctor.newInstance(objectVars, variableMap, sub, null);
            } catch (NoSuchMethodException e) {
                ctor = qtfClass.getConstructor(Map.class, StringSubstitutor.class, Class.class);
                listener = ctor.newInstance(variableMap, sub, null);
            }
            org.antlr.v4.runtime.tree.ParseTreeWalker.DEFAULT.walk((org.antlr.v4.runtime.tree.ParseTreeListener) listener, edgeFilterCtx);
            java.lang.reflect.Method getFilter = qtfClass.getMethod("getFilter");
            return getFilter.invoke(listener);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    protected Set<String> ontologySrcIdsByDst(DataDomain dataDomain, String predicate, String dst, Object edgeFilter) {
        if (dataDomain == null) return Collections.emptySet();
        try {
            var cdi = jakarta.enterprise.inject.spi.CDI.current();
            if (cdi == null) return Collections.emptySet();
            Class<?> edgeRepoClass = Class.forName("com.e2eq.ontology.repo.OntologyEdgeRepo");
            var selection = cdi.select(edgeRepoClass);
            if (selection.isUnsatisfied()) return Collections.emptySet();
            Object edgeRepo = selection.get();
            if (edgeFilter != null) {
                for (java.lang.reflect.Method m : edgeRepoClass.getMethods()) {
                    if (m.getName().equals("srcIdsByDst") && m.getParameterCount() == 4 && m.getParameterTypes()[0].equals(DataDomain.class)) {
                        Object filterArray = java.lang.reflect.Array.newInstance(m.getParameterTypes()[3].getComponentType(), 1);
                        java.lang.reflect.Array.set(filterArray, 0, edgeFilter);
                        Object result = m.invoke(edgeRepo, dataDomain, predicate, dst, filterArray);
                        if (result instanceof Set<?> set) return (Set<String>) set;
                    }
                }
            }
            java.lang.reflect.Method method = edgeRepoClass.getMethod("srcIdsByDst", DataDomain.class, String.class, String.class);
            Object result = method.invoke(edgeRepo, dataDomain, predicate, dst);
            if (result instanceof Set<?> set) return (Set<String>) set;
        } catch (Throwable ignored) { }
        return Collections.emptySet();
    }

    @SuppressWarnings("unchecked")
    protected Set<String> ontologyDstIdsBySrc(DataDomain dataDomain, String predicate, String src, Object edgeFilter) {
        if (dataDomain == null) return Collections.emptySet();
        try {
            var cdi = jakarta.enterprise.inject.spi.CDI.current();
            if (cdi == null) return Collections.emptySet();
            Class<?> edgeRepoClass = Class.forName("com.e2eq.ontology.repo.OntologyEdgeRepo");
            var selection = cdi.select(edgeRepoClass);
            if (selection.isUnsatisfied()) return Collections.emptySet();
            Object edgeRepo = selection.get();
            if (edgeFilter != null) {
                for (java.lang.reflect.Method m : edgeRepoClass.getMethods()) {
                    if (m.getName().equals("dstIdsBySrc") && m.getParameterCount() == 4 && m.getParameterTypes()[0].equals(DataDomain.class)) {
                        Object filterArray = java.lang.reflect.Array.newInstance(m.getParameterTypes()[3].getComponentType(), 1);
                        java.lang.reflect.Array.set(filterArray, 0, edgeFilter);
                        Object result = m.invoke(edgeRepo, dataDomain, predicate, src, filterArray);
                        if (result instanceof Set<?> set) return (Set<String>) set;
                    }
                }
            }
            java.lang.reflect.Method method = edgeRepoClass.getMethod("dstIdsBySrc", DataDomain.class, String.class, String.class);
            Object result = method.invoke(edgeRepo, dataDomain, predicate, src);
            if (result instanceof Set<?> set) return (Set<String>) set;
        } catch (Throwable ignored) { }
        return Collections.emptySet();
    }

    private void pushIdPredicate(Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            predicateStack.push(wrap(node -> false, false));
            return;
        }
        Set<String> targetIds = new HashSet<>(ids);
        predicateStack.push(wrap(node -> {
            if (node == null || !node.isObject()) return false;
            if (matchesId(node, targetIds)) return true;
            if (node.hasNonNull("resource") && node.get("resource").isObject()) {
                if (matchesId(node.get("resource"), targetIds)) return true;
            }
            return false;
        }, false));
    }

    private static boolean matchesId(JsonNode node, Set<String> targetIds) {
        if (node.hasNonNull("id") && targetIds.contains(node.get("id").asText())) {
            return true;
        }
        if (node.hasNonNull("_id")) {
            JsonNode idNode = node.get("_id");
            if (idNode.isObject() && idNode.hasNonNull("$oid") && targetIds.contains(idNode.get("$oid").asText())) {
                return true;
            }
            if (targetIds.contains(idNode.asText())) {
                return true;
            }
        }
        if (node.hasNonNull("refName") && targetIds.contains(node.get("refName").asText())) {
            return true;
        }
        return false;
    }

    private static String cleanString(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            if (trimmed.length() >= 2) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
        }
        return trimmed;
    }

    // ---- helpers ----
    private boolean isCaseSensitive(BIAPIQueryParser.CaseModeContext ctx) {
        return ctx != null && ctx.getStart().getType() == BIAPIQueryParser.CASE_SENSITIVE;
    }

    private Predicate<JsonNode> makeBasicPredicate(Token fieldTok, Token opTok, Object valueObj) {
        return makeBasicPredicate(fieldTok, opTok, valueObj, false);
    }

    private Predicate<JsonNode> makeBasicPredicate(Token fieldTok, Token opTok, Object valueObj, boolean caseSensitive) {
        String field = fieldTok.getText();
        CommonToken originalToken = valueObj instanceof CommonToken tok ? tok : null;
        boolean caseInsensitiveStringEquality = originalToken != null
                && !caseSensitive
                && (opTok.getType() == BIAPIQueryParser.EQ || opTok.getType() == BIAPIQueryParser.NEQ)
                && (originalToken.getType() == BIAPIQueryParser.STRING
                        || originalToken.getType() == BIAPIQueryParser.TEXT
                        || originalToken.getType() == BIAPIQueryParser.QUOTED_STRING);
        Object value = coerceFromTokenMaybeSubstitute(valueObj);
        return switch (opTok.getType()) {
            case BIAPIQueryParser.EQ -> node -> compare(node, field, value, caseInsensitiveStringEquality);
            case BIAPIQueryParser.NEQ -> node -> !compare(node, field, value, caseInsensitiveStringEquality);
            case BIAPIQueryParser.GT -> node -> relational(node, field, value, ">");
            case BIAPIQueryParser.GTE -> node -> relational(node, field, value, ">=");
            case BIAPIQueryParser.LT -> node -> relational(node, field, value, "<");
            case BIAPIQueryParser.LTE -> node -> relational(node, field, value, "<=");
            case BIAPIQueryParser.IN -> node -> inRelational(node, field, value);
            default -> throw new IllegalArgumentException("Operator invalid:" + opTok.getText());
        };
    }

    private boolean inRelational(JsonNode node, String field, Object value) {
        Set<Object> set = new HashSet<>();
        if (value instanceof Collection<?> coll) {
            for (Object item : coll) {
                set.add(coerceValue(item));
            }
        } else if (value != null && value.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < len; i++) {
                set.add(coerceValue(java.lang.reflect.Array.get(value, i)));
            }
        } else if (value instanceof String s) {
            for (String part : s.split(",")) {
                if (!part.isBlank()) set.add(coerceValue(part.trim()));
            }
        } else if (value != null) {
            set.add(coerceValue(value));
        }

        JsonNode fv = getNodeAt(node, field);
        if (fv == null || fv.isMissingNode() || fv.isNull()) return false;
        if (fv.isArray()) {
            for (JsonNode el : fv) {
                if (containsMatch(set, coerceFromJsonNode(el))) return true;
            }
            return false;
        }
        return containsMatch(set, coerceFromJsonNode(fv));
    }

    private Object coerceFromTokenMaybeSubstitute(Object tokenOrValue) {
        if (tokenOrValue instanceof CommonToken tok) {
            int t = tok.getType();
            if (variableMap != null && t != BIAPIQueryParser.VARIABLE) {
                tokenOrValue = sub.replace(tok.getText());
            }
            switch (t) {
                case BIAPIQueryParser.STRING, BIAPIQueryParser.TEXT, BIAPIQueryParser.QUOTED_STRING -> { return tok.getText(); }
                case BIAPIQueryParser.OID -> { return new ObjectId(tok.getText()); }
                case BIAPIQueryParser.VARIABLE -> {
                    String text = tok.getText();
                    String varName = (text.startsWith("${") && text.endsWith("}"))
                            ? text.substring(2, text.length() - 1)
                            : text;
                    if (objectVars != null && objectVars.containsKey(varName)) {
                        return objectVars.get(varName);
                    }
                    String rep = (sub != null) ? sub.replace(text) : text;
                    return coerceValue(rep);
                }
                case BIAPIQueryParser.NUMBER -> { return Double.parseDouble(tok.getText()); }
                case BIAPIQueryParser.WHOLENUMBER -> { return Long.parseLong(tok.getText()); }
                case BIAPIQueryParser.DATE -> {
                    String s = tok.getText();
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
                    try { return LocalDate.parse(s, formatter); } catch (DateTimeParseException e) { throw new IllegalArgumentException("Unable to parse date: " + s, e); }
                }
                case BIAPIQueryParser.DATETIME -> {
                    String s = tok.getText();
                    try { return Date.from(ZonedDateTime.parse(s, DateTimeFormatter.ISO_DATE_TIME).toInstant()); } catch (DateTimeParseException e) { throw new IllegalArgumentException("Unable to parse datetime: " + s, e); }
                }
                default -> throw new IllegalArgumentException("Unknown token type:" + t);
            }
        }
        return coerceValue(tokenOrValue);
    }

    private String resolveTextValue(Object tokenOrValue) {
        if (tokenOrValue instanceof CommonToken tok) {
            String text = tok.getText();
            if (tok.getType() == BIAPIQueryParser.VARIABLE) {
                return (sub != null) ? sub.replace(text) : text;
            }
            if (sub != null) {
                return sub.replace(text);
            }
            return text;
        }
        return tokenOrValue == null ? null : tokenOrValue.toString();
    }

    private Object coerceValue(Object v) {
        if (v == null) return null;
        if (v instanceof org.bson.types.ObjectId) return v;
        if (v instanceof Number || v instanceof Boolean || v instanceof Date) return v;
        if (v instanceof CharSequence s) {
            String str = s.toString();
            if (str.matches("^[a-fA-F0-9]{24}$")) { try { return new ObjectId(str); } catch (Exception ignored) {} }
            if ("true".equalsIgnoreCase(str) || "false".equalsIgnoreCase(str)) return Boolean.parseBoolean(str);
            if (str.matches("^-?\\d+$")) { try { return Long.parseLong(str); } catch (NumberFormatException ignored) {} }
            if (str.matches("^-?\\d+\\.\\d+$")) { try { return Double.parseDouble(str); } catch (NumberFormatException ignored) {} }
            try { return Date.from(ZonedDateTime.parse(str).toInstant()); } catch (Exception ignored) {}
            try { return LocalDate.parse(str); } catch (Exception ignored) {}
            return str;
        }
        return v;
    }

    private String escapeRegexChars(String input) {
        if (input == null) return null;
        return SPECIAL_REGEX_CHARS.matcher(input).replaceAll("\\\\$0");
    }

    /**
     * Tokenize a text(...) search string into lower-case word terms.
     * Uses the same non-letter/non-digit split as {@link #valueHasWord} so
     * {@code text("foo-bar")} matches a document field value {@code "foo-bar"}.
     */
    static List<String> tokenizeSearchTerms(String search) {
        if (search == null || search.isBlank()) return List.of();
        List<String> terms = new ArrayList<>();
        for (String word : WORD_SPLIT.split(search.toLowerCase())) {
            if (!word.isEmpty()) {
                terms.add(word);
            }
        }
        return terms;
    }

    /**
     * In-memory approximation of MongoDB $text: tokenized contains-any with
     * whole-word matching (not substring). Does not implement stemming/stop words.
     */
    private boolean matchesTextSearch(JsonNode node, List<String> terms) {
        if (node == null || terms == null || terms.isEmpty()) return false;
        List<String> values = new ArrayList<>();
        collectTextValues(node, values);
        if (values.isEmpty()) return false;
        for (String term : terms) {
            for (String value : values) {
                if (valueHasWord(value, term)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True if {@code value} contains {@code term} as a whole word (case-insensitive). */
    static boolean valueHasWord(String value, String term) {
        if (value == null || term == null || term.isEmpty()) return false;
        for (String word : WORD_SPLIT.split(value.toLowerCase())) {
            if (!word.isEmpty() && word.equals(term)) {
                return true;
            }
        }
        return false;
    }

    private void collectTextValues(JsonNode node, List<String> values) {
        if (node == null) return;
        if (node.isTextual()) {
            values.add(node.asText());
            return;
        }
        if (node.isArray()) {
            for (JsonNode el : node) {
                collectTextValues(el, values);
            }
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> collectTextValues(entry.getValue(), values));
        }
    }

    private boolean containsMatch(Set<Object> set, Object candidate) {
        for (Object v : set) {
            if (equalsWithDateFlex(v, candidate)) return true;
        }
        return false;
    }

    private boolean compare(JsonNode node, String path, Object rhs, boolean ignoreCase) {
        JsonNode lhsNode = getNodeAt(node, path);
        if (lhsNode == null || lhsNode.isMissingNode()) return false;
        if (lhsNode.isArray()) {
            for (JsonNode el : lhsNode) if (compareScalar(coerceFromJsonNode(el), rhs, ignoreCase) == 0) return true;
            return false;
        }
        return compareScalar(coerceFromJsonNode(lhsNode), rhs, ignoreCase) == 0;
    }

    private boolean relational(JsonNode node, String path, Object rhs, String op) {
        JsonNode lhsNode = getNodeAt(node, path);
        if (lhsNode == null || lhsNode.isMissingNode()) return false;
        if (lhsNode.isArray()) {
            for (JsonNode el : lhsNode) if (compareRelational(coerceFromJsonNode(el), rhs, op)) return true;
            return false;
        }
        return compareRelational(coerceFromJsonNode(lhsNode), rhs, op);
    }

    private boolean compareRelational(Object lhs, Object rhs, String op) {
        int cmp = compareScalar(lhs, rhs);
        return switch (op) {
            case ">" -> cmp > 0; case ">=" -> cmp >= 0; case "<" -> cmp < 0; case "<=" -> cmp <= 0; default -> false; };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compareScalar(Object lhs, Object rhs) {
        return compareScalar(lhs, rhs, false);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compareScalar(Object lhs, Object rhs, boolean ignoreCase) {
        if (equalsWithDateFlex(lhs, rhs)) return 0;

        if (lhs instanceof Number ln && rhs instanceof Number rn) {
            int d = Double.compare(ln.doubleValue(), rn.doubleValue());
            return (d == 0) ? 0 : (d < 0 ? -1 : 1);
        }
        if (lhs instanceof Boolean lb && rhs instanceof Boolean rb) return lb.equals(rb) ? 0 : 1;

        if (lhs instanceof Date ld) {
            Date rd = (rhs instanceof Date) ? (Date) rhs : null;
            if (rd != null) return Long.compare(ld.getTime(), rd.getTime());
        }
        if (lhs instanceof LocalDate ldl) {
            LocalDate rdl = (rhs instanceof LocalDate) ? (LocalDate) rhs : null;
            if (rdl != null) return ldl.compareTo(rdl);
        }
        if (lhs instanceof ObjectId lo && rhs instanceof ObjectId ro) return lo.equals(ro) ? 0 : 1;

        String ls = String.valueOf(lhs);
        String rs = String.valueOf(rhs);
        if (ignoreCase && ls.equalsIgnoreCase(rs)) return 0;
        int s = ls.compareTo(rs);
        return s == 0 ? 0 : (s < 0 ? -1 : 1);
    }

    private boolean equalsWithDateFlex(Object a, Object b) {
        if (Objects.equals(a, b)) return true;
        if (a instanceof Date ad && b instanceof Date bd) return ad.getTime() == bd.getTime();
        if (a instanceof LocalDate al && b instanceof LocalDate bl) return al.isEqual(bl);
        return false;
    }

    private Boolean asBoolean(JsonNode v) {
        if (v == null || v.isMissingNode() || v.isNull()) return null;
        if (v.isBoolean()) return v.asBoolean();
        if (v.isNumber()) return v.asInt() != 0;
        if (v.isTextual()) {
            String s = v.asText();
            if ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) return Boolean.parseBoolean(s);
        }
        return null;
    }

    private JsonNode getNodeAt(JsonNode root, String path) {
        if (root == null || path == null || path.isEmpty()) return root;
        String[] parts = path.split("\\.");
        JsonNode cur = root;
        for (String p : parts) {
            if (cur == null) return cur;
            if (cur.isArray()) {
                // flatten: step into each element
                List<JsonNode> next = new ArrayList<>();
                for (JsonNode el : cur) next.add(el.path(p));
                // create a synthetic array node? Not needed; return a pseudo array as a node is not possible here.
                // Instead, wrap back into a simple ArrayNode-like behaviour: we return a marker by joining; but easier: evaluate at leaf level only.
                // To keep simple, if array encountered mid-path, collapse to an array of the next nodes by using a small helper.
                com.fasterxml.jackson.databind.node.ArrayNode arr = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
                for (JsonNode n : next) arr.add(n);
                cur = arr;
            } else {
                cur = cur.path(p);
            }
        }
        return cur;
    }

    private boolean exists(JsonNode root, String path) {
        JsonNode n = getNodeAt(root, path);
        return n != null && !n.isMissingNode() && !n.isNull();
    }

    private Object coerceFromJsonNode(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        if (n.isNumber()) {
            if (n.isIntegralNumber()) return n.asLong();
            return n.asDouble();
        }
        if (n.isBoolean()) return n.asBoolean();
        String s = n.asText();
        return coerceValue(s);
    }

    private List<Object> collectInValues(BIAPIQueryParser.ValueListExprContext list, String fieldName) {
        List<Object> values = new ArrayList<>();
        boolean singleVarOnly = false;
        if (list != null && list.children != null) {
            long varCount = list.children.stream().filter(c -> c instanceof org.antlr.v4.runtime.tree.TerminalNode tn && tn.getSymbol().getType() == BIAPIQueryParser.VARIABLE).count();
            long valCount = list.children.stream().filter(c -> c instanceof org.antlr.v4.runtime.tree.TerminalNode tn && switch (tn.getSymbol().getType()) {
                case BIAPIQueryParser.STRING, BIAPIQueryParser.TEXT, BIAPIQueryParser.QUOTED_STRING, BIAPIQueryParser.VARIABLE, BIAPIQueryParser.OID, BIAPIQueryParser.REFERENCE -> true; default -> false; }).count();
            singleVarOnly = (varCount == 1 && valCount == 1);
        }
        if (singleVarOnly && sub != null) {
            org.antlr.v4.runtime.tree.TerminalNode varNode = list.children.stream().filter(c -> c instanceof org.antlr.v4.runtime.tree.TerminalNode tn && tn.getSymbol().getType() == BIAPIQueryParser.VARIABLE).map(c -> (org.antlr.v4.runtime.tree.TerminalNode)c).findFirst().orElse(null);
            String varTokenText = varNode != null ? varNode.getText() : null;
            String varName = (varTokenText != null) ? varTokenText.substring(2, varTokenText.length()-1) : null;
            Object v = (varName != null) ? objectVars.get(varName) : null;
            if (v instanceof Collection<?> coll) coll.forEach(item -> values.add(coerceValue(item)));
            else if (v != null && v.getClass().isArray()) { int len = java.lang.reflect.Array.getLength(v); for (int i=0;i<len;i++) values.add(coerceValue(java.lang.reflect.Array.get(v,i))); }
            else {
                String substituted = sub.replace(varTokenText);
                if (substituted != null && !substituted.isBlank()) for (String part : substituted.split(",")) values.add(coerceValue(part.trim()));
            }
        } else {
            for (var child : list.children) {
                if (!(child instanceof org.antlr.v4.runtime.tree.TerminalNode tn)) continue;
                int t = tn.getSymbol().getType();
                switch (t) {
                    case BIAPIQueryParser.QUOTED_STRING -> values.add(tn.getText());
                    case BIAPIQueryParser.STRING, BIAPIQueryParser.TEXT -> values.add(coerceValue(tn.getText()));
                    case BIAPIQueryParser.OID -> values.add(new ObjectId(tn.getText()));
                    case BIAPIQueryParser.REFERENCE -> values.add(tn.getText());
                    case BIAPIQueryParser.VARIABLE -> { String replaced = (sub != null) ? sub.replace(tn.getText()) : tn.getText(); if (replaced != null && !replaced.isBlank()) for (String part : replaced.split(",")) values.add(coerceValue(part.trim())); }
                    default -> {}
                }
            }
        }
        return values;
    }
}
