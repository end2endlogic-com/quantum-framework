package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.imports.ImportSession;
import com.e2eq.framework.model.persistent.morphia.ImportSessionRepo;
import jakarta.ws.rs.Path;

@Path("/integration/importsessions")
public class ImportSessionResource extends BaseResource<ImportSession, ImportSessionRepo> {
    protected ImportSessionResource(ImportSessionRepo repo) {
        super(repo);
    }
}
