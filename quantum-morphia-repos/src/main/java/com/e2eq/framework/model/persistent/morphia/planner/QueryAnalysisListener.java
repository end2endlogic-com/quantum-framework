package com.e2eq.framework.model.persistent.morphia.planner;

import com.e2eq.framework.grammar.BIAPIQueryBaseListener;
import com.e2eq.framework.grammar.BIAPIQueryParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Walks the parsed query and records new grammar features that affect planning.
 * Currently only expand(path) is supported.
 */
public class QueryAnalysisListener extends BIAPIQueryBaseListener {
    private final List<String> expandPaths = new ArrayList<>();

    @Override
    public void enterExpandExpr(BIAPIQueryParser.ExpandExprContext ctx) {
        // ctx does not expose a single path field; reconstruct the content inside parentheses
        String text = ctx.getText(); // e.g., "expand(items[*].product)"
        int lp = text.indexOf('(');
        int rp = text.lastIndexOf(')');
        if (lp >= 0 && rp > lp) {
            String inner = text.substring(lp + 1, rp).trim();
            if (!inner.isEmpty()) {
                expandPaths.add(inner);
            }
        }
    }

    public boolean hasExpansions() {
        return !expandPaths.isEmpty();
    }

    public List<String> getExpandPaths() {
        return Collections.unmodifiableList(expandPaths);
    }
}
