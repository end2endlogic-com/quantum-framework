package com.e2eq.ontology.core;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.*;

/**
 * Centralized alias resolver for ontology properties (predicates).
 *
 * Source of truth for aliases is typically annotations in the model (e.g., @OntologyProperty(aliases=...))
 * and/or external configuration. This resolver offers a simple map-based API that other layers can
 * depend on to canonicalize predicate names used in policies, grammar, and repositories.
 *
 * If no aliases are registered, canonical(name) simply returns the provided name.
 */
@ApplicationScoped
public class OntologyAliasResolver {

    private final Map<String, String> aliasToCanonical = new HashMap<>();
    private final Map<String, Set<String>> canonicalToAllNames = new HashMap<>();

    /**
     * Register a canonical predicate id and an optional set of aliases.
     */
    public void register(String canonical, Collection<String> aliases) {
        if (canonical == null || canonical.isBlank()) return;
        String canon = canonical;
        Set<String> all = canonicalToAllNames.computeIfAbsent(canon, k -> new HashSet<>());
        all.add(canon);
        if (aliases == null) return;
        for (String a : aliases) {
            if (a == null || a.isBlank()) continue;
            aliasToCanonical.put(a.toLowerCase(Locale.ROOT), canon);
            all.add(a);
        }
    }

    /**
     * Resolve any known alias to its canonical predicate. If unknown, returns the input unchanged.
     */
    public String canonical(String name) {
        if (name == null) return null;
        String key = name.toLowerCase(Locale.ROOT);
        return aliasToCanonical.getOrDefault(key, name);
    }

    /**
     * All names (canonical + aliases) for a given canonical id, if registered.
     */
    public Set<String> allNames(String canonical) {
        if (canonical == null) return Collections.emptySet();
        return canonicalToAllNames.getOrDefault(canonical, Set.of(canonical));
    }
}
