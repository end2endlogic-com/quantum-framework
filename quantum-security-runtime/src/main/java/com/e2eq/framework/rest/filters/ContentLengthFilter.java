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
            Log.debugf("** Filter call in ContentLengthFilter.filter() being ignored method:%s  url:%s", requestContext.getRequest().getMethod(), requestContext.getUriInfo().getAbsolutePath().toString());
        }
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws JsonProcessingException {
        // Ensure JSON responses are sent with an explicit, correct Content-Length (some
        // serverless / API-gateway fronts — e.g. AWS API Gateway / Lambda / ALB — require
        // a fixed Content-Length and do not tolerate chunked Transfer-Encoding). We do this
        // by buffering the serialized JSON into a String so the body length is known up
        // front: the server then emits a correct fixed-length Content-Length itself and
        // does NOT fall back to chunked encoding.
        //
        // We deliberately do NOT hand-add a Content-Length header. The previous code did
        // (headers.add(CONTENT_LENGTH, entity.getBytes().length)) while the entity was still
        // a streamed POJO — so on responses past the write-buffer threshold (~8KB) the server
        // chunked the body AND a manual Content-Length was appended. A response carrying both
        // Transfer-Encoding: chunked and Content-Length is illegal HTTP; upstream proxies
        // (Cloud Run / Envoy) reset the stream with "protocol error, reset before headers",
        // which surfaces to clients as a 502. Buffering to a String + letting the server own
        // the header satisfies the serverless constraint without the conflict.
        if (responseContext.getEntity() != null && responseContext.getMediaType() != null
                && responseContext.getMediaType().toString().equals("application/json;charset=UTF-8")) {
            String entity = mapper.writeValueAsString(responseContext.getEntity());
            responseContext.setEntity(entity);
        }
        else if (responseContext.getMediaType()!= null && "text/event-stream".equals(responseContext.getMediaType().toString())){
           responseContext.getHeaders().putSingle("Cache-Control", "no-cache, no-transform");
           responseContext.getHeaders().putSingle("Pragma", "no-cache");
           responseContext.getHeaders().putSingle("Content-Type", "text/event-stream");
           responseContext.getHeaders().putSingle("Connection", "keep-alive");
           responseContext.getHeaders().putSingle("Transfer-Encoding", "chunked");
        }

        /* else {
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
