package com.e2eq.framework.api.tools;

import com.e2eq.framework.model.persistent.morphia.ToolDefinitionRepo;
import com.e2eq.framework.model.persistent.tools.ToolDefinition;
import com.e2eq.framework.model.persistent.tools.ToolType;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * Seeds the six Query Gateway tools as {@link ToolDefinition} entities in each realm on first use.
 * Also runs at startup for the default realm so tools are available without a prior API call.
 */
@ApplicationScoped
public class GatewayToolSeeder {

    private static final String CATEGORY = "quantum.query";
    private static final List<String> AVAILABLE_AS = List.of("tool", "workflowStep", "mcpTool");
    private static final String SOURCE = "manual";

    @Inject
    ToolDefinitionRepo toolDefinitionRepo;

    @ConfigProperty(name = "quantum.realm.testRealm", defaultValue = "defaultRealm")
    String defaultRealm;

    void onStart(@Observes StartupEvent event) {
        seedRealm(defaultRealm);
    }

    /**
     * Ensures the six gateway tools exist in the given realm. Idempotent: only inserts if missing.
     *
     * @param realm realm id
     */
    public void seedRealm(String realm) {
        if (realm == null || realm.isBlank()) {
            return;
        }
        for (ToolDefinition t : buildGatewayTools()) {
            if (toolDefinitionRepo.findByRefName(realm, t.getRefName()).isEmpty()) {
                toolDefinitionRepo.save(realm, t);
            }
        }
    }

    private static List<ToolDefinition> buildGatewayTools() {
        return List.of(
            tool("query_rootTypes", "List all available entity types (rootTypes).", false),
            tool("query_plan", "Get the execution plan for a query (FILTER vs AGGREGATION mode).", false),
            tool("query_find", "Find entities matching a query. Supports paging and sort.", false),
            tool("query_save", "Create or update an entity.", true),
            tool("query_delete", "Delete one entity by ID.", true),
            tool("query_deleteMany", "Delete all entities matching a query.", true)
        );
    }

    private static ToolDefinition tool(String refName, String description, boolean hasSideEffects) {
        ToolDefinition t = new ToolDefinition();
        t.setRefName(refName);
        t.setName(refName);
        t.setDescription(description);
        t.setCategory(CATEGORY);
        t.setToolType(ToolType.QUANTUM_QUERY);
        t.setHasSideEffects(hasSideEffects);
        t.setIdempotent(!hasSideEffects);
        t.setAvailableAs(AVAILABLE_AS);
        t.setEnabled(true);
        t.setSource(SOURCE);
        return t;
    }
}
