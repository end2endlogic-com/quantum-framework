package com.e2eq.framework.grammar.sql;

import com.e2eq.framework.grammar.BIAPIQueryBaseListener;
import com.e2eq.framework.grammar.BIAPIQueryLexer;
import com.e2eq.framework.grammar.BIAPIQueryParser;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Translates a BIAPIQuery grammar string into a parameterized SQL {@code WHERE} clause.
 *
 * <p><b>Tier 1 — scalar-filter pushdown (fail-closed).</b> This translator is deliberately
 * conservative: it handles scalar comparison predicates joined by {@code &&} (AND) and REFUSES
 * anything it cannot translate exactly — {@code ||} (OR), parenthesized grouping, {@code !!}
 * (NOT), {@code :^}/{@code :!^} (IN/NIN), regex, elemMatch, text, and the ontology edge/expand
 * functions all throw {@link UnsupportedQueryException}. Because the base listener's default
 * methods are no-ops, EVERY leaf expression is overridden here — a silently-dropped predicate
 * would be a governance leak, so an unhandled construct fails the whole translation.</p>
 *
 * <p>The governed input is {@code userQuery && <policy rule filter strings>} composed upstream by
 * the gateway (RuleContext); the policy constraints are already in the filter before translation,
 * so the emitted SQL is governed with no security logic re-implemented here. Field names are
 * validated as safe identifiers; all values are parameterized ({@code ?}).</p>
 */
public class QueryToSqlListener extends BIAPIQueryBaseListener {

    /** Thrown when the query contains a construct this tier does not translate. Fail closed. */
    public static class UnsupportedQueryException extends RuntimeException {
        public UnsupportedQueryException(String message) {
            super(message);
        }
    }

    /** The rendered clause + its ordered parameters. */
    public static final class SqlWhere {
        public final String whereClause;
        public final List<Object> params;

        SqlWhere(String whereClause, List<Object> params) {
            this.whereClause = whereClause;
            this.params = params;
        }
    }

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*");

    private final List<String> predicates = new ArrayList<>();
    private final List<Object> params = new ArrayList<>();

    /**
     * Parse a BIAPIQuery string and render a governed SQL WHERE clause. Parse errors and
     * unsupported constructs both fail closed (throw), never produce partial SQL.
     */
    public static SqlWhere translate(String query) {
        BIAPIQueryLexer lexer = new BIAPIQueryLexer(CharStreams.fromString(query == null ? "" : query));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        BIAPIQueryParser parser = new BIAPIQueryParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                    int charPositionInLine, String msg, RecognitionException e) {
                throw new UnsupportedQueryException("Query parse error at " + line + ":" + charPositionInLine + " — " + msg);
            }
        });
        QueryToSqlListener listener = new QueryToSqlListener();
        ParseTreeWalker.DEFAULT.walk(listener, parser.query());
        return new SqlWhere(String.join(" AND ", listener.predicates), listener.params);
    }

    private String col(org.antlr.v4.runtime.Token field) {
        String name = field.getText();
        if (name == null || !SAFE_IDENTIFIER.matcher(name).matches()) {
            throw new UnsupportedQueryException("Unsafe or empty field identifier: " + name);
        }
        return name;
    }

    private String comparator(org.antlr.v4.runtime.Token op) {
        switch (op.getType()) {
            case BIAPIQueryParser.EQ: return "=";
            case BIAPIQueryParser.NEQ: return "<>";
            case BIAPIQueryParser.LT: return "<";
            case BIAPIQueryParser.GT: return ">";
            case BIAPIQueryParser.LTE: return "<=";
            case BIAPIQueryParser.GTE: return ">=";
            default:
                throw new UnsupportedQueryException("Operator not supported in SQL tier 1: " + op.getText());
        }
    }

    private void addComparison(org.antlr.v4.runtime.Token field, org.antlr.v4.runtime.Token op, Object value) {
        predicates.add(col(field) + " " + comparator(op) + " ?");
        params.add(value);
    }

    // --- Supported scalar comparisons -------------------------------------------------

    @Override
    public void enterStringExpr(BIAPIQueryParser.StringExprContext ctx) {
        addComparison(ctx.field, ctx.op, ctx.value.getText());
    }

    @Override
    public void enterQuotedExpr(BIAPIQueryParser.QuotedExprContext ctx) {
        addComparison(ctx.field, ctx.op, ctx.value.getText());
    }

    @Override
    public void enterReferenceExpr(BIAPIQueryParser.ReferenceExprContext ctx) {
        addComparison(ctx.field, ctx.op, ctx.value.getText());
    }

    @Override
    public void enterDateExpr(BIAPIQueryParser.DateExprContext ctx) {
        addComparison(ctx.field, ctx.op, ctx.value.getText());
    }

    @Override
    public void enterDateTimeExpr(BIAPIQueryParser.DateTimeExprContext ctx) {
        addComparison(ctx.field, ctx.op, ctx.value.getText());
    }

    @Override
    public void enterNumberExpr(BIAPIQueryParser.NumberExprContext ctx) {
        addComparison(ctx.field, ctx.op, Double.valueOf(ctx.value.getText()));
    }

    @Override
    public void enterWholenumberExpr(BIAPIQueryParser.WholenumberExprContext ctx) {
        addComparison(ctx.field, ctx.op, Long.valueOf(ctx.value.getText()));
    }

    @Override
    public void enterBooleanExpr(BIAPIQueryParser.BooleanExprContext ctx) {
        addComparison(ctx.field, ctx.op, Boolean.valueOf("true".equalsIgnoreCase(ctx.value.getText())));
    }

    @Override
    public void enterNullExpr(BIAPIQueryParser.NullExprContext ctx) {
        if (ctx.op.getType() == BIAPIQueryParser.EQ) {
            predicates.add(col(ctx.field) + " IS NULL");
        } else if (ctx.op.getType() == BIAPIQueryParser.NEQ) {
            predicates.add(col(ctx.field) + " IS NOT NULL");
        } else {
            throw new UnsupportedQueryException("Null comparison operator not supported: " + ctx.op.getText());
        }
    }

    @Override
    public void enterExistsExpr(BIAPIQueryParser.ExistsExprContext ctx) {
        predicates.add(col(ctx.field) + " IS NOT NULL");
    }

    // --- Fail closed: everything not translated above -------------------------------------

    @Override
    public void enterExprOp(BIAPIQueryParser.ExprOpContext ctx) {
        // AND is the only supported combiner in tier 1; OR needs the full composition tier.
        if (ctx.op.getType() != BIAPIQueryParser.AND) {
            throw new UnsupportedQueryException("Only '&&' (AND) is supported in SQL tier 1; got '" + ctx.op.getText() + "'");
        }
    }

    @Override
    public void enterExprGroup(BIAPIQueryParser.ExprGroupContext ctx) {
        throw new UnsupportedQueryException("Parenthesized grouping is not supported in SQL tier 1");
    }

    @Override
    public void enterInExpr(BIAPIQueryParser.InExprContext ctx) {
        throw new UnsupportedQueryException("IN/NIN is not supported in SQL tier 1");
    }

    @Override
    public void enterNotExpr(BIAPIQueryParser.NotExprContext ctx) {
        throw new UnsupportedQueryException("NOT (!!) is not supported in SQL tier 1");
    }

    @Override
    public void enterRegexExpr(BIAPIQueryParser.RegexExprContext ctx) {
        throw new UnsupportedQueryException("Regex is not supported in SQL tier 1");
    }

    @Override
    public void enterElemMatchExpr(BIAPIQueryParser.ElemMatchExprContext ctx) {
        throw new UnsupportedQueryException("elemMatch is not supported in SQL tier 1");
    }

    @Override
    public void enterTextExpr(BIAPIQueryParser.TextExprContext ctx) {
        throw new UnsupportedQueryException("text() is not supported in SQL tier 1");
    }

    @Override
    public void enterHasEdgeExpr(BIAPIQueryParser.HasEdgeExprContext ctx) {
        throw new UnsupportedQueryException("hasEdge is a relation join (tier 2), not supported in SQL tier 1");
    }

    @Override
    public void enterHasOutgoingEdgeExpr(BIAPIQueryParser.HasOutgoingEdgeExprContext ctx) {
        throw new UnsupportedQueryException("hasOutgoingEdge is a relation join (tier 2), not supported in SQL tier 1");
    }

    @Override
    public void enterHasIncomingEdgeExpr(BIAPIQueryParser.HasIncomingEdgeExprContext ctx) {
        throw new UnsupportedQueryException("hasIncomingEdge is a relation join (tier 2), not supported in SQL tier 1");
    }

    @Override
    public void enterExpandExpr(BIAPIQueryParser.ExpandExprContext ctx) {
        throw new UnsupportedQueryException("expand is a relation join (tier 2), not supported in SQL tier 1");
    }
}
