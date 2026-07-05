package com.e2eq.framework.grammar.sql;

import com.e2eq.framework.grammar.BIAPIQueryBaseListener;
import com.e2eq.framework.grammar.BIAPIQueryLexer;
import com.e2eq.framework.grammar.BIAPIQueryParser;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates a BIAPIQuery grammar string into a parameterized SQL {@code WHERE} clause.
 *
 * <p><b>Federation tier 1.</b> Handles scalar comparison predicates, {@code IN/NIN}, {@code null}
 * and {@code exists}, composed with {@code &&} (AND) and {@code ||} (OR) using the SAME
 * precedence as the reference {@code QueryToFilterListener} — <b>AND binds tighter than OR</b>
 * (e.g. {@code a || b && c} → {@code a OR (b AND c)}). It <b>fails closed</b> on anything it
 * cannot translate exactly: explicit grouping {@code (...)}, {@code !!} (NOT), regex, elemMatch,
 * text, and the ontology edge/expand functions all throw {@link UnsupportedQueryException} —
 * a silently dropped/mis-composed predicate would be a governance leak.</p>
 *
 * <p><b>Order-safety:</b> composition pops predicates in reverse, which would misalign positional
 * {@code ?} parameters. So leaves emit named placeholders ({@code :pN}) into a map, and the final
 * clause is scanned left-to-right, replacing each {@code :pN} with {@code ?} and emitting params in
 * string-appearance order — guaranteeing {@code ?}↔param alignment regardless of composition order.</p>
 *
 * <p>The governed input is composed upstream by the gateway
 * ({@code userQuery && <RuleContext policy filter strings>}); policy is already in the filter, so
 * the emitted SQL is governed with no security logic re-implemented here. Variables ({@code ${x}})
 * are expected to be substituted upstream before translation.</p>
 */
public class QueryToSqlListener extends BIAPIQueryBaseListener {

    public static class UnsupportedQueryException extends RuntimeException {
        public UnsupportedQueryException(String message) { super(message); }
    }

    /** The rendered clause (positional {@code ?}) + its ordered parameters. */
    public static final class SqlWhere {
        public final String whereClause;
        public final List<Object> params;
        SqlWhere(String whereClause, List<Object> params) { this.whereClause = whereClause; this.params = params; }
    }

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*");
    private static final Pattern NAMED_PARAM = Pattern.compile(":p\\d+");

    private final Deque<String> sqlStack = new ArrayDeque<>();
    private final Deque<Integer> opTypeStack = new ArrayDeque<>();
    private final Map<String, Object> named = new LinkedHashMap<>();
    /** Maps an ontology field reference to its physical source column. Identity by default. */
    private final Function<String, String> columnResolver;
    private int paramCounter = 0;
    private int queryDepth = 0;

    private QueryToSqlListener(Function<String, String> columnResolver) {
        this.columnResolver = columnResolver == null ? Function.identity() : columnResolver;
    }

    /** Translate with the field name used verbatim as the column (identity mapping). */
    public static SqlWhere translate(String query) {
        return translate(query, Function.<String>identity());
    }

    /**
     * Translate, mapping each ontology field reference to its physical source column via
     * {@code fieldToColumn} (the reverse of the source→ontology mapping). Unmapped fields pass
     * through unchanged. Both the field and the resolved column are validated as safe identifiers.
     */
    public static SqlWhere translate(String query, Map<String, String> fieldToColumn) {
        Function<String, String> resolver = (fieldToColumn == null)
                ? Function.identity()
                : field -> fieldToColumn.getOrDefault(field, field);
        return translate(query, resolver);
    }

    public static SqlWhere translate(String query, Function<String, String> columnResolver) {
        BIAPIQueryLexer lexer = new BIAPIQueryLexer(CharStreams.fromString(query == null ? "" : query));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        BIAPIQueryParser parser = new BIAPIQueryParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener() {
            @Override public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int pos, String msg, RecognitionException e) {
                throw new UnsupportedQueryException("Query parse error at " + line + ":" + pos + " — " + msg);
            }
        });
        QueryToSqlListener listener = new QueryToSqlListener(columnResolver);
        ParseTreeWalker.DEFAULT.walk(listener, parser.query());
        return listener.render();
    }

    private SqlWhere render() {
        String body = sqlStack.isEmpty() ? "" : sqlStack.peek();
        List<Object> positional = new ArrayList<>();
        Matcher m = NAMED_PARAM.matcher(body);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group();
            if (!named.containsKey(name)) {
                throw new UnsupportedQueryException("Internal: unknown named parameter " + name);
            }
            positional.add(named.get(name));
            m.appendReplacement(out, "?");
        }
        m.appendTail(out);
        return new SqlWhere(out.toString(), positional);
    }

    private String newParam(Object value) {
        String name = ":p" + (paramCounter++);
        named.put(name, value);
        return name;
    }

    private String col(Token field) {
        String name = field.getText();
        if (name == null || !SAFE_IDENTIFIER.matcher(name).matches()) {
            throw new UnsupportedQueryException("Unsafe or empty field identifier: " + name);
        }
        String column = columnResolver.apply(name);
        if (column == null || !SAFE_IDENTIFIER.matcher(column).matches()) {
            throw new UnsupportedQueryException("Unsafe or empty resolved column for field: " + name);
        }
        return column;
    }

    private String comparator(Token op) {
        switch (op.getType()) {
            case BIAPIQueryParser.EQ: return "=";
            case BIAPIQueryParser.NEQ: return "<>";
            case BIAPIQueryParser.LT: return "<";
            case BIAPIQueryParser.GT: return ">";
            case BIAPIQueryParser.LTE: return "<=";
            case BIAPIQueryParser.GTE: return ">=";
            default: throw new UnsupportedQueryException("Operator not supported in SQL tier 1: " + op.getText());
        }
    }

    private void pushComparison(Token field, Token op, Object value) {
        sqlStack.push(col(field) + " " + comparator(op) + " " + newParam(value));
    }

    private static String combine(List<String> parts, String connector) {
        if (parts.size() == 1) return parts.get(0);
        return "(" + String.join(connector, parts) + ")";
    }

    // --- composition (mirrors QueryToFilterListener; AND binds tighter than OR) ---------

    @Override public void enterQuery(BIAPIQueryParser.QueryContext ctx) { queryDepth++; }

    @Override
    public void exitQuery(BIAPIQueryParser.QueryContext ctx) {
        queryDepth--;
        if (queryDepth == 0 && (!opTypeStack.isEmpty() || sqlStack.size() > 1)) {
            buildComposite();
        }
    }

    private void buildComposite() {
        List<String> andParts = new ArrayList<>();
        List<String> orParts = new ArrayList<>();
        while (!opTypeStack.isEmpty()) {
            int op = opTypeStack.pop();
            if (op == BIAPIQueryParser.AND) {
                if (sqlStack.isEmpty()) throw new UnsupportedQueryException("AND without right-hand predicate");
                andParts.add(sqlStack.pop());
            } else if (op == BIAPIQueryParser.OR) {
                if (sqlStack.isEmpty()) throw new UnsupportedQueryException("OR without predicate");
                if (!andParts.isEmpty()) {
                    andParts.add(sqlStack.pop());
                    orParts.add(combine(andParts, " AND "));
                    andParts = new ArrayList<>();
                } else {
                    orParts.add(sqlStack.pop());
                }
            } else {
                throw new UnsupportedQueryException("Unsupported composition operator");
            }
        }
        int inner = sqlStack.size();
        if (!andParts.isEmpty()) {
            if (inner <= 0) throw new UnsupportedQueryException("AND composition missing left-hand predicate");
            andParts.add(sqlStack.pop());
            String andCombined = combine(andParts, " AND ");
            if (!orParts.isEmpty()) {
                orParts.add(andCombined);
                sqlStack.push(combine(orParts, " OR "));
            } else {
                sqlStack.push(andCombined);
            }
            return;
        }
        if (!orParts.isEmpty()) {
            String left = null;
            if (inner > 1) {
                List<String> leftList = new ArrayList<>();
                for (int i = 0; i < inner; i++) leftList.add(0, sqlStack.pop());
                left = combine(leftList, " AND ");
            } else if (inner == 1) {
                left = sqlStack.pop();
            }
            if (left != null) orParts.add(left);
            sqlStack.push(combine(orParts, " OR "));
            return;
        }
        if (inner > 1) {
            List<String> tmp = new ArrayList<>();
            for (int i = 0; i < inner; i++) tmp.add(0, sqlStack.pop());
            sqlStack.push(combine(tmp, " AND "));
        }
    }

    @Override
    public void enterExprOp(BIAPIQueryParser.ExprOpContext ctx) {
        int t = ctx.op.getType();
        if (t != BIAPIQueryParser.AND && t != BIAPIQueryParser.OR) {
            throw new UnsupportedQueryException("Unsupported combiner: " + ctx.op.getText());
        }
        opTypeStack.push(t);
    }

    // --- leaves: scalar comparisons ------------------------------------------------------

    @Override public void enterStringExpr(BIAPIQueryParser.StringExprContext ctx) { pushComparison(ctx.field, ctx.op, ctx.value.getText()); }
    @Override public void enterQuotedExpr(BIAPIQueryParser.QuotedExprContext ctx) { pushComparison(ctx.field, ctx.op, ctx.value.getText()); }
    @Override public void enterReferenceExpr(BIAPIQueryParser.ReferenceExprContext ctx) { pushComparison(ctx.field, ctx.op, ctx.value.getText()); }
    @Override public void enterDateExpr(BIAPIQueryParser.DateExprContext ctx) { pushComparison(ctx.field, ctx.op, ctx.value.getText()); }
    @Override public void enterDateTimeExpr(BIAPIQueryParser.DateTimeExprContext ctx) { pushComparison(ctx.field, ctx.op, ctx.value.getText()); }
    @Override public void enterNumberExpr(BIAPIQueryParser.NumberExprContext ctx) { pushComparison(ctx.field, ctx.op, Double.valueOf(ctx.value.getText())); }
    @Override public void enterWholenumberExpr(BIAPIQueryParser.WholenumberExprContext ctx) { pushComparison(ctx.field, ctx.op, Long.valueOf(ctx.value.getText())); }

    @Override
    public void enterBooleanExpr(BIAPIQueryParser.BooleanExprContext ctx) {
        pushComparison(ctx.field, ctx.op, Boolean.valueOf("true".equalsIgnoreCase(ctx.value.getText())));
    }

    @Override
    public void enterNullExpr(BIAPIQueryParser.NullExprContext ctx) {
        if (ctx.op.getType() == BIAPIQueryParser.EQ) {
            sqlStack.push(col(ctx.field) + " IS NULL");
        } else if (ctx.op.getType() == BIAPIQueryParser.NEQ) {
            sqlStack.push(col(ctx.field) + " IS NOT NULL");
        } else {
            throw new UnsupportedQueryException("Null comparison operator not supported: " + ctx.op.getText());
        }
    }

    @Override
    public void enterExistsExpr(BIAPIQueryParser.ExistsExprContext ctx) {
        sqlStack.push(col(ctx.field) + " IS NOT NULL");
    }

    @Override
    public void enterInExpr(BIAPIQueryParser.InExprContext ctx) {
        String column = col(ctx.field);
        boolean negated = ctx.op.getType() == BIAPIQueryParser.NIN;
        List<String> placeholders = new ArrayList<>();
        BIAPIQueryParser.ValueListExprContext list = ctx.value;
        if (list != null && list.children != null) {
            for (Object child : list.children) {
                if (child instanceof TerminalNode tn) {
                    int t = tn.getSymbol().getType();
                    switch (t) {
                        case BIAPIQueryParser.STRING:
                        case BIAPIQueryParser.QUOTED_STRING:
                        case BIAPIQueryParser.OID:
                        case BIAPIQueryParser.REFERENCE:
                            placeholders.add(newParam(tn.getText()));
                            break;
                        case BIAPIQueryParser.VARIABLE:
                            throw new UnsupportedQueryException("Unresolved variable in IN list — substitute upstream");
                        default:
                            // COMMA / brackets — skip
                    }
                }
            }
        }
        if (placeholders.isEmpty()) throw new UnsupportedQueryException("Empty IN list");
        sqlStack.push(column + (negated ? " NOT IN (" : " IN (") + String.join(", ", placeholders) + ")");
    }

    // --- fail closed: not translated in tier 1 ------------------------------------------

    @Override public void enterExprGroup(BIAPIQueryParser.ExprGroupContext ctx) {
        throw new UnsupportedQueryException("Parenthesized grouping is not supported yet (tier 1)");
    }
    @Override public void enterNotExpr(BIAPIQueryParser.NotExprContext ctx) {
        throw new UnsupportedQueryException("NOT (!!) is not supported yet (tier 1)");
    }
    @Override public void enterRegexExpr(BIAPIQueryParser.RegexExprContext ctx) {
        throw new UnsupportedQueryException("Regex is not supported in SQL tier 1");
    }
    @Override public void enterElemMatchExpr(BIAPIQueryParser.ElemMatchExprContext ctx) {
        throw new UnsupportedQueryException("elemMatch is not supported in SQL tier 1");
    }
    @Override public void enterTextExpr(BIAPIQueryParser.TextExprContext ctx) {
        throw new UnsupportedQueryException("text() is not supported in SQL tier 1");
    }
    @Override public void enterHasEdgeExpr(BIAPIQueryParser.HasEdgeExprContext ctx) {
        throw new UnsupportedQueryException("hasEdge is a relation join (tier 2)");
    }
    @Override public void enterHasOutgoingEdgeExpr(BIAPIQueryParser.HasOutgoingEdgeExprContext ctx) {
        throw new UnsupportedQueryException("hasOutgoingEdge is a relation join (tier 2)");
    }
    @Override public void enterHasIncomingEdgeExpr(BIAPIQueryParser.HasIncomingEdgeExprContext ctx) {
        throw new UnsupportedQueryException("hasIncomingEdge is a relation join (tier 2)");
    }
    @Override public void enterExpandExpr(BIAPIQueryParser.ExpandExprContext ctx) {
        throw new UnsupportedQueryException("expand is a relation join (tier 2)");
    }
}
