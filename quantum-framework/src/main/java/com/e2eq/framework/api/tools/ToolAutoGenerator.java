package com.e2eq.framework.api.tools;

import com.e2eq.framework.model.persistent.morphia.ToolDefinitionRepo;
import com.e2eq.framework.model.persistent.tools.ToolDefinition;
import com.e2eq.framework.model.persistent.tools.ToolType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Generates ToolDefinitions from root type names (e.g. search_Order, get_Order).
 * Generated tools use QUANTUM_QUERY/QUANTUM_API and are wired so that search_X invokes query_find with rootType=X.
 * Supports @ToolGeneration-style operations: search, get, create, update, delete, count.
 */
@ApplicationScoped
public class ToolAutoGenerator {

    private static final String CATEGORY = "quantum.crud";
    private static final String SOURCE = "auto-generated";

    /** Operations that can be generated. search/get/count are QUANTUM_QUERY; create/update/delete are QUANTUM_API. */
    public static final Set<String> ALL_OPERATIONS = Set.of("search", "get", "create", "update", "delete", "count");

    @Inject
    ToolDefinitionRepo toolDefinitionRepo;

    /**
     * Generates tool definitions for the given root type simple names (search_* only).
     * For full CRUD (search, get, create, update, delete, count) use {@link #generateFullCrudForRootTypes}.
     *
     * @param realm     realm id
     * @param rootTypes simple names (e.g. Order, Customer)
     * @param persist  if true, save to realm (only if refName not already present)
     * @return list of generated (and optionally persisted) tools
     */
    public List<ToolDefinition> generateForRootTypes(String realm, List<String> rootTypes, boolean persist) {
        return generateFullCrudForRootTypes(realm, rootTypes, persist, Set.of());
    }

    /**
     * Generates tool definitions for the given root types with full CRUD operations.
     * Creates search_*, get_*, create_*, update_*, delete_*, count_* unless excluded.
     * Skips any refName that already exists when persist is true (do not overwrite).
     *
     * @param realm             realm id
     * @param rootTypes         simple names (e.g. Order, Customer)
     * @param persist           if true, save to realm
     * @param excludeOperations operations to skip (e.g. "delete", "create"). Allowed: search, get, create, update, delete, count
     * @return list of generated tools
     */
    public List<ToolDefinition> generateFullCrudForRootTypes(String realm, List<String> rootTypes, boolean persist,
                                                            Set<String> excludeOperations) {
        if (realm == null || realm.isBlank() || rootTypes == null || rootTypes.isEmpty()) {
            return List.of();
        }
        Set<String> exclude = excludeOperations != null ? excludeOperations : Set.of();
        List<ToolDefinition> out = new ArrayList<>();
        for (String type : rootTypes) {
            if (type == null || type.isBlank()) continue;
            if (!exclude.contains("search")) {
                addIfAbsent(out, realm, buildSearchTool(type), persist);
            }
            if (!exclude.contains("get")) {
                addIfAbsent(out, realm, buildGetTool(type), persist);
            }
            if (!exclude.contains("create")) {
                addIfAbsent(out, realm, buildCreateTool(type), persist);
            }
            if (!exclude.contains("update")) {
                addIfAbsent(out, realm, buildUpdateTool(type), persist);
            }
            if (!exclude.contains("delete")) {
                addIfAbsent(out, realm, buildDeleteTool(type), persist);
            }
            if (!exclude.contains("count")) {
                addIfAbsent(out, realm, buildCountTool(type), persist);
            }
        }
        return out;
    }

    private void addIfAbsent(List<ToolDefinition> out, String realm, ToolDefinition t, boolean persist) {
        if (persist && toolDefinitionRepo.findByRefName(realm, t.getRefName()).isPresent()) {
            return;
        }
        if (persist) {
            toolDefinitionRepo.save(realm, t);
        }
        out.add(t);
    }

    private static ToolDefinition buildSearchTool(String type) {
        ToolDefinition t = new ToolDefinition();
        t.setRefName("search_" + type);
        t.setName("Search " + type);
        t.setDescription("Search " + type + " entities. Calls query_find with rootType=" + type + ". Parameters: query, page, sort.");
        t.setCategory(CATEGORY);
        t.setToolType(ToolType.QUANTUM_QUERY);
        t.setSource(SOURCE);
        t.setEnabled(true);
        t.setHasSideEffects(false);
        return t;
    }

    private static ToolDefinition buildGetTool(String type) {
        ToolDefinition t = new ToolDefinition();
        t.setRefName("get_" + type);
        t.setName("Get " + type);
        t.setDescription("Get a single " + type + " by id. Parameters: id.");
        t.setCategory(CATEGORY);
        t.setToolType(ToolType.QUANTUM_QUERY);
        t.setSource(SOURCE);
        t.setEnabled(true);
        t.setHasSideEffects(false);
        return t;
    }

    private static ToolDefinition buildCreateTool(String type) {
        ToolDefinition t = new ToolDefinition();
        t.setRefName("create_" + type);
        t.setName("Create " + type);
        t.setDescription("Create a new " + type + " entity. Parameters: entity (object).");
        t.setCategory(CATEGORY);
        t.setToolType(ToolType.QUANTUM_API);
        t.setSource(SOURCE);
        t.setEnabled(true);
        t.setHasSideEffects(true);
        return t;
    }

    private static ToolDefinition buildUpdateTool(String type) {
        ToolDefinition t = new ToolDefinition();
        t.setRefName("update_" + type);
        t.setName("Update " + type);
        t.setDescription("Update an existing " + type + " entity. Parameters: id, entity (object).");
        t.setCategory(CATEGORY);
        t.setToolType(ToolType.QUANTUM_API);
        t.setSource(SOURCE);
        t.setEnabled(true);
        t.setHasSideEffects(true);
        return t;
    }

    private static ToolDefinition buildDeleteTool(String type) {
        ToolDefinition t = new ToolDefinition();
        t.setRefName("delete_" + type);
        t.setName("Delete " + type);
        t.setDescription("Delete a " + type + " by id. Parameters: id.");
        t.setCategory(CATEGORY);
        t.setToolType(ToolType.QUANTUM_API);
        t.setSource(SOURCE);
        t.setEnabled(true);
        t.setHasSideEffects(true);
        return t;
    }

    private static ToolDefinition buildCountTool(String type) {
        ToolDefinition t = new ToolDefinition();
        t.setRefName("count_" + type);
        t.setName("Count " + type);
        t.setDescription("Count " + type + " entities matching query. Parameters: query (optional).");
        t.setCategory(CATEGORY);
        t.setToolType(ToolType.QUANTUM_QUERY);
        t.setSource(SOURCE);
        t.setEnabled(true);
        t.setHasSideEffects(false);
        return t;
    }
}
