package com.e2eq.ontology.core;

import com.e2eq.ontology.annotations.DependsOn.Expand;

import java.util.List;

/**
 * Pure-data representation of a declarative computed-edge definition loaded
 * from YAML.
 *
 * <p>Example:</p>
 * <pre>
 * computed:
 *   - id: canSeeLocation
 *     domain: Associate
 *     range: Location
 *     via:
 *       hierarchy:
 *         node: Territory
 *         from: assignedTerritories
 *         expand: descendants     # descendants | ancestors | none
 *       list:
 *         field: locationLists
 *         mode: auto              # auto | static | dynamic
 *     dependsOn:
 *       - { type: Territory }
 *       - { type: LocationList }
 * </pre>
 *
 * <p>Spec is consumed by {@code DeclarativeComputedEdgeProvider} together with
 * an application-supplied {@code DeclarativeRuntime} that knows how to load
 * entities and walk hierarchies.</p>
 */
public record ComputedEdgeSpec(
        String id,
        String domain,
        String range,
        Hierarchy hierarchy,
        ListSpec list,
        List<String> dependsOnTypes
) {
    public record Hierarchy(String nodeType, String fromField, Expand expand) {}
    public record ListSpec(String field, String mode) {}
}
