package com.e2eq.framework.mcp;

import com.e2eq.framework.mcp.defect.Defect;
import com.e2eq.framework.mcp.defect.DefectRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Exposes defect tracking as MCP tools so agents (Claude, Cursor, etc.) can file,
 * list, inspect, and triage defects directly over MCP — the "defect API in MCP".
 *
 * <p>Delegates to {@link DefectRepo}, reusing the same realm resolution, security
 * context, and audit as the REST {@code /mcp/defects} surface. Results are returned
 * as JSON strings, matching the other MCP tool classes in this module.</p>
 *
 * @see com.e2eq.framework.mcp.defect.DefectResource
 */
@Authenticated
public class McpDefectTools {

    @Inject
    DefectRepo defectRepo;

    @Inject
    ObjectMapper objectMapper;

    @Tool(description = "File a new defect/issue. Returns the created defect including its generated refName. "
            + "Severity is one of low|medium|high|critical (default medium); the defect starts in status 'open'.")
    String defect_create(
            @ToolArg(description = "Short summary line for the defect") String title,
            @ToolArg(description = "Full description: what is wrong, how to reproduce, and relevant context") String description,
            @ToolArg(description = "Severity: low|medium|high|critical. Default: medium", required = false, defaultValue = "medium") String severity,
            @ToolArg(description = "Subsystem/area, e.g. 'quantum-ontology-service' (optional)", required = false) String area,
            @ToolArg(description = "Finer-grained component within the area (optional)", required = false) String component,
            @ToolArg(description = "Who is reporting this (optional; defaults to the caller identity)", required = false) String reporter) {
        try {
            Defect defect = new Defect();
            defect.setRefName("defect-" + UUID.randomUUID());
            defect.setTitle(title);
            defect.setDisplayName(title != null && !title.isBlank() ? title : defect.getRefName());
            defect.setDescription(description);
            defect.setSeverity(normalizeSeverity(severity));
            defect.setStatus("open");
            defect.setArea(area);
            defect.setComponent(component);
            defect.setReporter(reporter);
            Defect saved = defectRepo.save(defect);
            return objectMapper.writeValueAsString(toMap(saved));
        } catch (Exception e) {
            return errorJson("DefectCreateFailed", e);
        }
    }

    @Tool(description = "List defects, optionally filtered by status (open|in-progress|resolved|closed), "
            + "severity (low|medium|high|critical), and/or area. Filters are case-insensitive.")
    String defect_list(
            @ToolArg(description = "Optional status filter: open|in-progress|resolved|closed", required = false) String status,
            @ToolArg(description = "Optional severity filter: low|medium|high|critical", required = false) String severity,
            @ToolArg(description = "Optional area filter", required = false) String area) {
        try {
            List<Map<String, Object>> matches = defectRepo.getAllList().stream()
                    .filter(d -> blankOrEquals(status, d.getStatus()))
                    .filter(d -> blankOrEquals(severity, d.getSeverity()))
                    .filter(d -> blankOrEquals(area, d.getArea()))
                    .map(this::toMap)
                    .collect(Collectors.toList());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("count", matches.size());
            result.put("defects", matches);
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return errorJson("DefectListFailed", e);
        }
    }

    @Tool(description = "Get a single defect by its refName.")
    String defect_get(
            @ToolArg(description = "The defect refName (as returned by defect_create / defect_list)") String refName) {
        try {
            Optional<Defect> found = defectRepo.findByRefName(refName);
            if (found.isEmpty()) {
                return "{\"error\":\"DefectNotFound\",\"message\":\"No defect with refName '" + escapeJson(refName) + "'\"}";
            }
            return objectMapper.writeValueAsString(toMap(found.get()));
        } catch (Exception e) {
            return errorJson("DefectGetFailed", e);
        }
    }

    @Tool(description = "Update a defect's triage fields by refName. Only non-blank arguments are applied. "
            + "Use this to change status (open|in-progress|resolved|closed), record a resolution, reassign, or re-prioritize.")
    String defect_update(
            @ToolArg(description = "The defect refName to update") String refName,
            @ToolArg(description = "New status: open|in-progress|resolved|closed (optional)", required = false) String status,
            @ToolArg(description = "Resolution notes (optional)", required = false) String resolution,
            @ToolArg(description = "Reassign to (optional)", required = false) String assignedTo,
            @ToolArg(description = "New severity: low|medium|high|critical (optional)", required = false) String severity) {
        try {
            Optional<Defect> found = defectRepo.findByRefName(refName);
            if (found.isEmpty()) {
                return "{\"error\":\"DefectNotFound\",\"message\":\"No defect with refName '" + escapeJson(refName) + "'\"}";
            }
            Defect defect = found.get();
            if (isPresent(status)) defect.setStatus(status.trim());
            if (isPresent(resolution)) defect.setResolution(resolution);
            if (isPresent(assignedTo)) defect.setAssignedTo(assignedTo.trim());
            if (isPresent(severity)) defect.setSeverity(normalizeSeverity(severity));
            Defect saved = defectRepo.save(defect);
            return objectMapper.writeValueAsString(toMap(saved));
        } catch (Exception e) {
            return errorJson("DefectUpdateFailed", e);
        }
    }

    private Map<String, Object> toMap(Defect d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("refName", d.getRefName());
        m.put("title", d.getTitle());
        m.put("description", d.getDescription());
        m.put("severity", d.getSeverity());
        m.put("status", d.getStatus());
        m.put("area", d.getArea());
        m.put("component", d.getComponent());
        m.put("reporter", d.getReporter());
        m.put("assignedTo", d.getAssignedTo());
        m.put("resolution", d.getResolution());
        m.put("relatedRefs", d.getRelatedRefs());
        m.put("tags", d.getTags());
        return m;
    }

    private static final List<String> SEVERITIES = Arrays.asList("low", "medium", "high", "critical");

    private String normalizeSeverity(String severity) {
        if (severity == null || severity.isBlank()) return "medium";
        String s = severity.trim().toLowerCase();
        return SEVERITIES.contains(s) ? s : "medium";
    }

    private boolean blankOrEquals(String filter, String value) {
        if (filter == null || filter.isBlank()) return true;
        return filter.trim().equalsIgnoreCase(value);
    }

    private boolean isPresent(String v) {
        return v != null && !v.isBlank();
    }

    private String errorJson(String code, Exception e) {
        return "{\"error\":\"" + code + "\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}";
    }

    private static String escapeJson(String s) {
        if (s == null) return "null";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
