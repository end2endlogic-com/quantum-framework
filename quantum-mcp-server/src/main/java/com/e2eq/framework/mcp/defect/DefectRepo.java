package com.e2eq.framework.mcp.defect;

import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class DefectRepo extends MorphiaRepo<Defect> {
}
