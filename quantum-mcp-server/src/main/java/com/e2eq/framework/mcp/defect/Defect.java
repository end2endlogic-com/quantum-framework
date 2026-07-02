package com.e2eq.framework.mcp.defect;

import com.e2eq.framework.model.persistent.base.FullBaseModel;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Indexed;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

/**
 * A tracked defect / issue. Persisted like any Quantum entity (realm-scoped, audited)
 * and exposed both as REST CRUD ({@code DefectResource}) and as MCP tools
 * ({@code McpDefectTools}) so agents can file and triage defects over MCP.
 *
 * <p>NOTE: a service that hosts these must include this package in
 * {@code quarkus.morphia.packages} so the entity is mapped:
 * {@code com.e2eq.framework.mcp.defect}.</p>
 */
@Entity("defect")
@RegisterForReflection
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class Defect extends FullBaseModel {

   /** Short summary line. */
   protected String title;

   /** Full description / reproduction / context. */
   protected String description;

   /** low | medium | high | critical */
   @Indexed
   protected String severity;

   /** open | in-progress | resolved | closed */
   @Indexed
   protected String status;

   /** Subsystem/area the defect is in (e.g. "quantum-ontology-service"). */
   @Indexed
   protected String area;

   /** Finer-grained component within the area. */
   protected String component;

   /** Who reported it. */
   protected String reporter;

   /** Who it is assigned to. */
   protected String assignedTo;

   /** Resolution notes once resolved/closed. */
   protected String resolution;

   /** Free-form references (code paths, PRs, related defect refNames, docs). */
   protected List<String> relatedRefs = new ArrayList<>();
   // NOTE: `tags` (String[]) is inherited from UnversionedBaseModel — do not redeclare.

   @Override
   public String bmFunctionalArea() {
      return "DEFECT";
   }

   @Override
   public String bmFunctionalDomain() {
      return "DEFECT";
   }
}
