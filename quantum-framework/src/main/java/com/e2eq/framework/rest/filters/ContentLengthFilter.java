package com.e2eq.framework.rest.filters;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.ext.Provider;


@Provider
public class ContentLengthFilter implements ContainerRequestFilter, ContainerResponseFilter {

    @Inject
    protected ObjectMapper mapper;


    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (Log.isDebugEnabled()) {
            // intentionally logging warning but only when debug is enabled
            Log.warnf("** Filter call in ContentLengthFilter.filter() being ignored method:%s  url:%s", requestContext.getRequest().getMethod(), requestContext.getUriInfo().getAbsolutePath().toString());
        }
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws JsonProcessingException {
        // Add content length header to responses
        if (responseContext.getEntity() != null && responseContext.getMediaType()!= null && responseContext.getMediaType().toString().equals("application/json;charset=UTF-8")) {
            String entity = mapper.writeValueAsString(responseContext.getEntity());
            responseContext.setEntity(entity);
            responseContext.getHeaders().add(HttpHeaders.CONTENT_LENGTH, entity.getBytes().length);
        }/* else {
            if (responseContext.getMediaType() != null && responseContext.getEntity() != null){
                Log.warnf("ContentLengthFilter: responseContext.getEntity() is null or not JSON:  Media Type:%s Path:%s", responseContext.getMediaType().toString(), requestContext.getUriInfo().getPath() );
                String entity = mapper.writeValueAsString(responseContext.getEntity());
                // WARNING: will cause issues when media type is not json, you will get " in there response potentially.
                //responseContext.setEntity(entity);
                //responseContext.getHeaders().add(HttpHeaders.CONTENT_LENGTH, entity.getBytes().length);
            } else if (responseContext.getEntity() != null) {
                Log.warnf("ContentLengthFilter: responseContext.getEntity() is not null but content media type is not null: Path: %s", requestContext.getUriInfo().getPath() );
                //String entity = mapper.writeValueAsString(responseContext.getEntity());
                //responseContext.setEntity(entity);
                //responseContext.getHeaders().add(HttpHeaders.CONTENT_LENGTH, entity.getBytes().length);
            }
        }
        if (responseContext.getMediaType() == null){
            Log.warnf("ContentLengthFilter: responseContext.getMediaType() is null: Path: %s", requestContext.getUriInfo().getPath());
        } */
    }
}
