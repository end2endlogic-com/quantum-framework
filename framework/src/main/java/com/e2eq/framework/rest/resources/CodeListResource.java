package com.e2eq.framework.rest.resources;


import com.e2eq.framework.model.persistent.base.CodeList;
import com.e2eq.framework.model.persistent.morphia.CodeListRepo;
import jakarta.ws.rs.Path;

@Path("/integration/codelists")
public class CodeListResource extends BaseResource<CodeList, CodeListRepo> {
    protected CodeListResource(CodeListRepo repo) {
        super(repo);
    }
}
