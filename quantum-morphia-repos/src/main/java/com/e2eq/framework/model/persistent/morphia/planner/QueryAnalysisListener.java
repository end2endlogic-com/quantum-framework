package com.e2eq.framework.model.persistent.morphia.planner;

import com.e2eq.framework.grammar.BIAPIQueryBaseListener;
import com.e2eq.framework.grammar.BIAPIQueryParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Walks the parsed query and records new grammar features that affect planning:
 * {@code expand(path)} over reference fields, and {@code expand(@relation)} over a named
 * ontology relation (a leading {@code @} marks the relation; it is not a field path).
 */
public class QueryAnalysisListener extends BIAPIQueryBaseListener {
    /** Marks an expand over an ontology relation rather than a field path. */
    public static final String RELATION_MARKER = "@";

    private final List<String> expandPaths = new ArrayList<>();
    private final List<String> relationExpands = new ArrayList<>();

    @Override
    public void enterExpandExpr(BIAPIQueryParser.ExpandExprContext ctx) {
        // ctx does not expose a single path field; reconstruct the content inside parentheses
        String text = ctx.getText(); // e.g., "expand(items[*].product)"
        int lp = text.indexOf('(');
        int rp = text.lastIndexOf(')');
        if (lp >= 0 && rp > lp) {
            String inner = text.substring(lp + 1, rp).trim();
            if (inner.startsWith(RELATION_MARKER)) {
                String relation = inner.substring(RELATION_MARKER.length()).trim();
                if (relation.isEmpty()) {
                    throw new IllegalArgumentException("expand(@) needs a relation name, e.g. expand(@hasShipment)");
                }
                if (!relationExpands.contains(relation)) {
                    relationExpands.add(relation);
                }
            } else if (!inner.isEmpty()) {
                expandPaths.add(inner);
            }
        }
    }

    public boolean hasExpansions() {
        return !expandPaths.isEmpty() || !relationExpands.isEmpty();
    }

    /** Field-path expands, compiled to {@code $lookup}. */
    public List<String> getExpandPaths() {
        return Collections.unmodifiableList(expandPaths);
    }

    /** Relation names from {@code expand(@relation)}, without the marker, in first-occurrence order. */
    public List<String> getRelationExpands() {
        return Collections.unmodifiableList(relationExpands);
    }
}
