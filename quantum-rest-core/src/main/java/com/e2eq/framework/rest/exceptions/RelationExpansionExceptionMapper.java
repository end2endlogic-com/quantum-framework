package com.e2eq.framework.rest.exceptions;

import com.e2eq.framework.model.persistent.morphia.planner.RelationExpansionException;
import io.quarkus.logging.Log;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** A refused {@code expand(@relation)} on any REST surface: its typed status and error body. */
@Provider
public class RelationExpansionExceptionMapper implements ExceptionMapper<RelationExpansionException> {

    @Override
    public Response toResponse(RelationExpansionException exception) {
        Log.warnf("Refused expand(@relation) (%s): %s", exception.code().wireValue(), exception.getMessage());
        return Response.status(exception.code().httpStatus()).type(MediaType.APPLICATION_JSON)
                .entity(exception.toErrorBody()).build();
    }
}
