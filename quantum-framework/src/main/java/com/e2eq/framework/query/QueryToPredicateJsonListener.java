package com.e2eq.framework.query;

import com.e2eq.framework.grammar.BIAPIQueryBaseListener;
import com.e2eq.framework.grammar.BIAPIQueryParser;
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

    private static final Pattern SPECIAL_REGEX_CHARS = Pattern.compile("[{}()\\[\\].+*?^$\\\\|\\-]");

    /**
     * Creates a listener with no variable substitution. Useful for queries without ${vars} or object variables.
     */
    public QueryToPredicateJsonListener() {
        this(null, null, null);
    }

    /**
     * Creates a listener that can substitute ${vars} in the query using the provided map.
     * @param variableMap name/value pairs used for StringSubstitutor (${var}) expansion; may be null
     */
    public QueryToPredicateJsonListener(Map<String, String> variableMap) {
        this(variableMap, null, variableMap != null ? new StringSubstitutor(variableMap) : null);
    }

    /**
     * Full constructor with variable and object variable support.
     * @param variableMap map for ${var} expansion (StringSubstitutor). May be null.
     * @param objectVars object-valued variables (e.g., for IN list expansion). May be null.
     * @param sub optional custom StringSubstitutor to use; if null and variableMap is non-null, a default will be created.
     */
    public QueryToPredicateJsonListener(Map<String, String> variableMap, Map<String, Object> objectVars, StringSubstitutor sub) {
        this.variableMap = variableMap;
        this.objectVars = (objectVars == null) ? Collections.emptyMap() : objectVars;
        this.sub = (sub != null) ? sub : (variableMap != null ? new StringSubstitutor(variableMap) : null);
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
            throw new IllegalStateException("Multiple text(...) clauses are not supported in a single query.");
        }
        textClauseSeen = true;
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
        return obj -> list.stream().allMatch(p -> p.test(obj));
    }
    private static Predicate<JsonNode> anyOf(List<Predicate<JsonNode>> list) {
        return obj -> list.stream().anyMatch(p -> p.test(obj));
    }

    // ---- Operators and groups ----
    /** {@inheritDoc} */
    @Override public void enterExprGroup(BIAPIQueryParser.ExprGroupContext ctx) { opTypeStack.push(ctx.lp.getType()); }
    /** {@inheritDoc} */
    @Override public void exitExprGroup(BIAPIQueryParser.ExprGroupContext ctx) { buildComposite(); }
    /** {@inheritDoc} */
    @Override public void enterExprOp(BIAPIQueryParser.ExprOpContext ctx) { opTypeStack.push(ctx.op.getType()); }

    /** {@inheritDoc} */
    @Override public void exitNotExpr(BIAPIQueryParser.NotExprContext ctx) {
        if (predicateStack.isEmpty()) throw new IllegalStateException("NOT with empty stack");
        Predicate<JsonNode> inner = predicateStack.pop();
        predicateStack.push(inner.negate());
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
        Pattern compiled = Pattern.compile(pattern);
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
        List<String> terms = Arrays.stream(trimmed.split("\\s+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .toList();
        predicateStack.push(node -> matchesTextSearch(node, terms));
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
    @Override public void enterStringExpr(BIAPIQueryParser.StringExprContext ctx) { predicateStack.push(makeBasicPredicate(ctx.field, ctx.op, ctx.value)); }
    /** {@inheritDoc} */
    @Override public void enterQuotedExpr(BIAPIQueryParser.QuotedExprContext ctx) { predicateStack.push(makeBasicPredicate(ctx.field, ctx.op, ctx.value)); }
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
    }
    /** {@inheritDoc} */
    @Override public void exitElemMatchExpr(BIAPIQueryParser.ElemMatchExprContext ctx) {
        int startOp = opTypeMarkers.pop();
        int startPred = predStackMarkers.pop();
        buildCompositeSince(startOp, startPred);
        if (predicateStack.size() <= startPred) throw new IllegalStateException("elemMatch produced no inner predicate");
        Predicate<JsonNode> inner = predicateStack.pop();
        String field = ctx.field.getText();
        predicateStack.push(node -> {
            JsonNode fv = getNodeAt(node, field);
            if (fv != null && fv.isArray()) {
                for (JsonNode el : fv) if (inner.test(el)) return true;
            }
            return false;
        });
    }

    // ---- helpers ----
    private Predicate<JsonNode> makeBasicPredicate(Token fieldTok, Token opTok, Object valueObj) {
        String field = fieldTok.getText();
        Object value = coerceFromTokenMaybeSubstitute(valueObj);
        return switch (opTok.getType()) {
            case BIAPIQueryParser.EQ -> node -> compare(node, field, value, ":");
            case BIAPIQueryParser.NEQ -> node -> !compare(node, field, value, ":");
            case BIAPIQueryParser.GT -> node -> relational(node, field, value, ">");
            case BIAPIQueryParser.GTE -> node -> relational(node, field, value, ">=");
            case BIAPIQueryParser.LT -> node -> relational(node, field, value, "<");
            case BIAPIQueryParser.LTE -> node -> relational(node, field, value, "<=");
            default -> throw new IllegalArgumentException("Operator invalid:" + opTok.getText());
        };
    }

    private Object coerceFromTokenMaybeSubstitute(Object tokenOrValue) {
        if (tokenOrValue instanceof CommonToken tok) {
            int t = tok.getType();
            if (variableMap != null && t != BIAPIQueryParser.VARIABLE) {
                tokenOrValue = sub.replace(tok.getText());
            }
            switch (t) {
                case BIAPIQueryParser.STRING, BIAPIQueryParser.QUOTED_STRING -> { return tok.getText(); }
                case BIAPIQueryParser.OID -> { return new ObjectId(tok.getText()); }
                case BIAPIQueryParser.VARIABLE -> { String rep = (sub != null) ? sub.replace(tok.getText()) : tok.getText(); return coerceValue(rep); }
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

    private boolean matchesTextSearch(JsonNode node, List<String> terms) {
        if (node == null || terms == null || terms.isEmpty()) return false;
        List<String> values = new ArrayList<>();
        collectTextValues(node, values);
        if (values.isEmpty()) return false;
        for (String term : terms) {
            for (String value : values) {
                if (value.toLowerCase().contains(term)) {
                    return true;
                }
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

    private boolean compare(JsonNode node, String path, Object rhs, String eq) {
        JsonNode lhsNode = getNodeAt(node, path);
        if (lhsNode == null || lhsNode.isMissingNode()) return false;
        if (lhsNode.isArray()) {
            for (JsonNode el : lhsNode) if (compareScalar(coerceFromJsonNode(el), rhs) == 0) return true;
            return false;
        }
        return compareScalar(coerceFromJsonNode(lhsNode), rhs) == 0;
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
                case BIAPIQueryParser.STRING, BIAPIQueryParser.QUOTED_STRING, BIAPIQueryParser.VARIABLE, BIAPIQueryParser.OID, BIAPIQueryParser.REFERENCE -> true; default -> false; }).count();
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
                    case BIAPIQueryParser.STRING -> values.add(coerceValue(tn.getText()));
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
