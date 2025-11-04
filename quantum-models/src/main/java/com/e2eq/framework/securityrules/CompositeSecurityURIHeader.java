package com.e2eq.framework.securityrules;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Non-intrusive DTO to carry list-valued header parts for one logical rule.
 * All values are lowercased for consistency. Empty lists are treated as wildcard ("*") during expansion.
 */
public final class CompositeSecurityURIHeader {
    private final List<String> identities;
    private final List<String> areas;
    private final List<String> functionalDomains;
    private final List<String> actions;

    public CompositeSecurityURIHeader(
            List<String> identities,
            List<String> areas,
            List<String> functionalDomains,
            List<String> actions) {
        this.identities = safeLowerDistinct(identities);
        this.areas = safeLowerDistinct(areas);
        this.functionalDomains = safeLowerDistinct(functionalDomains);
        this.actions = safeLowerDistinct(actions);
    }

    public List<String> getIdentities() { return identities; }
    public List<String> getAreas() { return areas; }
    public List<String> getFunctionalDomains() { return functionalDomains; }
    public List<String> getActions() { return actions; }

    private static List<String> safeLowerDistinct(List<String> in) {
        if (in == null || in.isEmpty()) return Collections.emptyList();
        return in.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .distinct()
                .collect(Collectors.toList());
    }
}
