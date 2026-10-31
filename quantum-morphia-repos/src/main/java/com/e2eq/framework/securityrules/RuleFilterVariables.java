package com.e2eq.framework.security.runtime;

import com.e2eq.framework.model.persistent.morphia.MorphiaUtils;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fail-closed checks for the {@code ${variable}} references in security rule filter strings.
 *
 * <p>A matched rule's filter is a security constraint. When a variable it references cannot be
 * resolved — no value in the bundle, a {@code null} value, or the resolver that should have
 * provided it threw — the constraint must not be dropped (which would widen access). Callers use
 * {@link #unresolved} to detect that case and then contribute {@link #matchNothing()} for reads or
 * deny for writes.</p>
 */
final class RuleFilterVariables {

    /** Same shape as the BIAPIQuery VARIABLE token: {@code ${IDENT(.IDENT)*}}. */
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}]+)}");

    private RuleFilterVariables() {
    }

    /** Variable names referenced by the given filter strings (null/blank strings are ignored). */
    static Set<String> referenced(String... filterStrings) {
        Set<String> names = new LinkedHashSet<>();
        if (filterStrings == null) {
            return names;
        }
        for (String filter : filterStrings) {
            if (filter == null || filter.isBlank()) {
                continue;
            }
            Matcher m = VARIABLE.matcher(filter);
            while (m.find()) {
                names.add(m.group(1).trim());
            }
        }
        return names;
    }

    /**
     * Variables referenced by the filter strings that the bundle cannot resolve to a non-null
     * value, including every variable whose resolver failed.
     */
    static Set<String> unresolved(MorphiaUtils.VariableBundle vars, String... filterStrings) {
        Set<String> referenced = referenced(filterStrings);
        if (referenced.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> missing = new LinkedHashSet<>();
        for (String name : referenced) {
            if (vars == null) {
                missing.add(name);
                continue;
            }
            if (vars.failedKeys.contains(name)) {
                missing.add(name);
                continue;
            }
            Object object = vars.objects != null ? vars.objects.get(name) : null;
            String string = vars.strings != null ? vars.strings.get(name) : null;
            if (object == null && string == null) {
                missing.add(name);
            }
        }
        return missing;
    }

    /** Names among {@code names} whose resolver threw (for diagnostics). */
    static Set<String> failed(MorphiaUtils.VariableBundle vars, Set<String> names) {
        Set<String> failed = new LinkedHashSet<>();
        if (vars == null || names == null) {
            return failed;
        }
        for (String name : names) {
            if (vars.failedKeys.contains(name)) {
                failed.add(name);
            }
        }
        return failed;
    }

    /** A Mongo filter that matches no document: {@code _id $in []}. */
    static Filter matchNothing() {
        return Filters.in("_id", Collections.emptyList());
    }
}
