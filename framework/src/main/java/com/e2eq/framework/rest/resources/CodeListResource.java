package com.e2eq.framework.rest.resources;


import com.e2eq.framework.model.persistent.base.CodeList;
import com.e2eq.framework.model.persistent.morphia.CodeListRepo;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

@Path("/integration/codelists")
public class CodeListResource extends BaseResource<CodeList, CodeListRepo> {
    protected CodeListResource(CodeListRepo repo) {
        super(repo);
    }


    @GET
    @Path("/findByCategoryAndKey")
    public Response findByCategoryAndKey(@QueryParam("category") String category,
                                         @QueryParam("key") String key) {
        if (category == null || key == null) {
            throw new IllegalArgumentException("Category and key must not be null");
        }

        return repo.findByCategoryAndKey(category, key)
                .map(Response::ok)
                .orElseThrow(() -> new NotFoundException("CodeList not found"))
                .build();
    }

}
