package com.e2eq.framework.rest.filters;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;

@Provider
public class ContentLengthFilter implements ContainerRequestFilter, ContainerResponseFilter {

    @Inject
    protected ObjectMapper jsonb;


    @Override
    public void filter(ContainerRequestContext requestContext) {

    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws JsonProcessingException {
        // Add content length header to responses

        if (responseContext.getEntity() != null && responseContext.getMediaType()!= null && responseContext.getMediaType().toString().equals("application/json;charset=UTF-8")) {
            String entity = jsonb.writeValueAsString(responseContext.getEntity());
            responseContext.setEntity(entity);
            responseContext.getHeaders().add(HttpHeaders.CONTENT_LENGTH, entity.getBytes().length);
        }
    }

}