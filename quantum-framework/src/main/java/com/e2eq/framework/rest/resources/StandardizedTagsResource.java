package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.base.StandardizedTags;
import com.e2eq.framework.model.persistent.morphia.StandardizedTagsRepo;
import jakarta.ws.rs.Path;

@Path("/integration/standardizedTags")
public class StandardizedTagsResource extends BaseResource<StandardizedTags, StandardizedTagsRepo>  {
    public StandardizedTagsResource(StandardizedTagsRepo repo) {
        super(repo);
    }
}
