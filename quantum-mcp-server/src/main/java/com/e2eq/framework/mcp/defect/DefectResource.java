package com.e2eq.framework.mcp.defect;

import com.e2eq.framework.rest.core.BaseResource;
import jakarta.ws.rs.Path;

/**
 * REST CRUD for {@link Defect} (also usable via the generated helixor-sdk-gen binding).
 * The same repo is exposed over MCP by {@link com.e2eq.framework.mcp.McpDefectTools}.
 */
@Path("/mcp/defects")
public class DefectResource extends BaseResource<Defect, DefectRepo> {
   protected DefectResource(DefectRepo repo) {
      super(repo);
   }
}
