package com.e2eq.ontology.mongo.it;

import com.e2eq.ontology.core.ComputedEdgeProvider;
import com.e2eq.ontology.core.ComputedEdgeProvenance;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.*;
import java.util.function.Function;

/**
 * Computed edge provider that creates "canSeeLocation" edges from Associates to Locations.
 *
 * <p>This provider implements the pattern:</p>
 * <ol>
 *   <li>Get the territories assigned to an associate</li>
 *   <li>Expand hierarchy to include sub-territories</li>
 *   <li>Collect all locations from each territory</li>
 *   <li>Create edges with provenance tracking the path</li>
 * </ol>
 *
 * <p>Example:</p>
 * <pre>
 * Associate (John) -> assignedTo -> Territory (West Region)
 * West Region -> parentOf -> Territory (California)
 * California -> containsLocations -> [Location (LA), Location (SF)]
 *
 * Results in computed edges:
 * John -> canSeeLocation -> LA (via West Region -> California)
 * John -> canSeeLocation -> SF (via West Region -> California)
 * </pre>
 */
@ApplicationScoped
public class AssociateCanSeeLocationProvider extends ComputedEdgeProvider<TerritoryAssociate> {

    // These are injected during test setup to avoid requiring full repos
    private Function<String, Optional<Territory>> territoryLoader;
    private Function<String, List<Territory>> childTerritoryLoader;

    /**
     * Set the territory loader function (for testing without full repo infrastructure).
     */
    public void setTerritoryLoader(Function<String, Optional<Territory>> loader) {
        this.territoryLoader = loader;
    }

    /**
     * Set the child territory loader function (for testing without full repo infrastructure).
     */
    public void setChildTerritoryLoader(Function<String, List<Territory>> loader) {
        this.childTerritoryLoader = loader;
    }

    @Override
    public Class<TerritoryAssociate> getSourceType() {
        return TerritoryAssociate.class;
    }

    @Override
    public String getPredicate() {
        return "canSeeLocation";
    }

    @Override
    public String getTargetTypeName() {
        return "TerritoryLocation";
    }

    @Override
    protected String extractId(TerritoryAssociate entity) {
        return entity.getId() != null ? entity.getId().toString() : entity.getRefName();
    }

    @Override
    protected Set<ComputedTarget> computeTargets(ComputationContext context, TerritoryAssociate associate) {
        Map<String, ComputedTarget> locationTargets = new LinkedHashMap<>();

        List<Territory> assignedTerritories = associate.getAssignedTerritories();
        if (assignedTerritories == null || assignedTerritories.isEmpty()) {
            return Collections.emptySet();
        }

        for (Territory directTerritory : assignedTerritories) {
            if (directTerritory == null) continue;

            String territoryId = directTerritory.getId() != null
                    ? directTerritory.getId().toString()
                    : directTerritory.getRefName();

            // Load full territory if needed (may have lazy-loaded reference)
            Territory fullTerritory = loadTerritory(territoryId).orElse(directTerritory);

            // Process direct territory
            processTerritory(context, fullTerritory, true, associate, locationTargets);

            // Process child territories (hierarchy expansion)
            List<Territory> children = getChildTerritories(territoryId);
            for (Territory child : children) {
                processTerritory(context, child, false, associate, locationTargets);
            }
        }

        return new HashSet<>(locationTargets.values());
    }

    private void processTerritory(
            ComputationContext context,
            Territory territory,
            boolean isDirectAssignment,
            TerritoryAssociate associate,
            Map<String, ComputedTarget> locationTargets) {

        String territoryId = territory.getId() != null
                ? territory.getId().toString()
                : territory.getRefName();

        List<TerritoryLocation> locations = territory.getLocations();
        if (locations == null || locations.isEmpty()) {
            return;
        }

        for (TerritoryLocation location : locations) {
            if (location == null) continue;

            String locationId = location.getId() != null
                    ? location.getId().toString()
                    : location.getRefName();

            // Build provenance for this path
            context.clearProvenance();
            context.addHierarchyContribution(
                    territoryId,
                    "Territory",
                    territory.getRefName(),
                    isDirectAssignment
            );

            ComputedEdgeProvenance prov = context.buildProvenance(
                    getSourceTypeName(),
                    extractId(associate)
            );

            // Only add if not already present (first path wins for simplicity)
            if (!locationTargets.containsKey(locationId)) {
                locationTargets.put(locationId, new ComputedTarget(locationId, prov));
            }
        }
    }

    private Optional<Territory> loadTerritory(String territoryId) {
        if (territoryLoader != null) {
            return territoryLoader.apply(territoryId);
        }
        return Optional.empty();
    }

    private List<Territory> getChildTerritories(String parentId) {
        if (childTerritoryLoader != null) {
            return childTerritoryLoader.apply(parentId);
        }
        return Collections.emptyList();
    }

    @Override
    public Set<Class<?>> getDependencyTypes() {
        // When these entity types change, affected associates need recomputation
        return Set.of(Territory.class, TerritoryLocation.class);
    }

    @Override
    public Set<String> getAffectedSourceIds(ComputationContext context, Class<?> dependencyType, String dependencyId) {
        // This would normally query the database to find associates affected by the change.
        // For testing purposes, this returns empty - the test manages this directly.
        return Collections.emptySet();
    }
}
