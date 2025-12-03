package com.e2eq.framework;

import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.rest.filters.SecurityFilter;
import jakarta.ws.rs.core.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.ws.rs.container.ContainerRequestContext;

import java.io.InputStream;
import java.net.URI;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies SecurityFilter.determineResourceContext() path and method inference behavior
 * without requiring a full Quarkus runtime. We stub minimal parts of ContainerRequestContext/UriInfo.
 */
public class SecurityFilterResourceContextInferenceTest {

    SecurityFilter filter;

    @BeforeEach
    void init() {
        filter = new SecurityFilter();
        SecurityContext.clear();
    }

    @AfterEach
    void cleanup() {
        SecurityContext.clear();
    }

    @Test
    void three_segment_path_sets_area_domain_action() {
        StubRequestContext req = new StubRequestContext("/orders/order/view", "GET");
        ResourceContext rc = filter.determineResourceContext(req);
        assertEquals("orders", rc.getArea());
        assertEquals("order", rc.getFunctionalDomain());
        assertEquals("view", rc.getAction());
    }

    @Test
    void four_segment_path_sets_resource_id() {
        StubRequestContext req = new StubRequestContext("/orders/order/update/123", "PUT");
        ResourceContext rc = filter.determineResourceContext(req);
        assertEquals("orders", rc.getArea());
        assertEquals("order", rc.getFunctionalDomain());
        assertEquals("update", rc.getAction());
        assertEquals("123", rc.getResourceId());
    }

    @Test
    void two_segment_path_infers_action_from_http_method() {
        StubRequestContext getReq = new StubRequestContext("/billing/invoice", "GET");
        ResourceContext rcGet = filter.determineResourceContext(getReq);
        assertEquals("VIEW", rcGet.getAction().toUpperCase(Locale.ROOT));

        StubRequestContext postReq = new StubRequestContext("/billing/invoice", "POST");
        ResourceContext rcPost = filter.determineResourceContext(postReq);
        assertEquals("CREATE", rcPost.getAction().toUpperCase(Locale.ROOT));

        StubRequestContext putReq = new StubRequestContext("/billing/invoice", "PUT");
        ResourceContext rcPut = filter.determineResourceContext(putReq);
        assertEquals("UPDATE", rcPut.getAction().toUpperCase(Locale.ROOT));

        StubRequestContext deleteReq = new StubRequestContext("/billing/invoice", "DELETE");
        ResourceContext rcDelete = filter.determineResourceContext(deleteReq);
        assertEquals("DELETE", rcDelete.getAction().toUpperCase(Locale.ROOT));
    }

    // ---- Minimal stubs ----
    static class StubRequestContext implements ContainerRequestContext {
        private final String path;
        private final String method;
        private final UriInfo uriInfo;
        private final MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();

        StubRequestContext(String path, String method) {
            this.path = path;
            this.method = method;
            this.uriInfo = new StubUriInfo(path);
        }

        @Override public Object getProperty(String name) { return null; }

       @Override
       public Collection<String> getPropertyNames () {
          return List.of();
       }

       @Override public void setProperty(String name, Object object) {}
        @Override public void removeProperty(String name) {}
        @Override public UriInfo getUriInfo() { return uriInfo; }
        @Override public void setRequestUri(URI requestUri) {}
        @Override public void setRequestUri(URI baseUri, URI requestUri) {}

       @Override
       public Request getRequest () {
          return null;
       }

       @Override public String getMethod() { return method; }
        @Override public void setMethod(String method) {}
        @Override public MultivaluedMap<String, String> getHeaders() { return headers; }
        @Override public String getHeaderString(String name) { return headers.getFirst(name); }
        @Override public Date getDate() { return null; }
        @Override public Locale getLanguage() { return null; }
        @Override public int getLength() { return 0; }
        @Override public MediaType getMediaType() { return null; }
        @Override public List<MediaType> getAcceptableMediaTypes() { return List.of(); }
        @Override public List<Locale> getAcceptableLanguages() { return List.of(); }
        @Override public Map<String, Cookie> getCookies() { return Map.of(); }
        @Override public boolean hasEntity() { return false; }
        @Override public InputStream getEntityStream() { return InputStream.nullInputStream(); }
        @Override public void setEntityStream(InputStream input) {}
        @Override public jakarta.ws.rs.core.SecurityContext getSecurityContext() { return null; }
        @Override public void setSecurityContext(jakarta.ws.rs.core.SecurityContext context) {}
        @Override public void abortWith(jakarta.ws.rs.core.Response response) {}

        // Deprecated methods may be absent depending on JAX-RS version; keep class compiling with current deps.
    }

    static class StubUriInfo implements UriInfo {
        private final String rawPath;
        StubUriInfo(String path) { this.rawPath = path; }
        @Override public String getPath() { return rawPath; }
        @Override public String getPath(boolean decode) { return rawPath; }
        @Override public List<PathSegment> getPathSegments() { return List.of(); }
        @Override public List<PathSegment> getPathSegments(boolean decode) { return List.of(); }
        @Override public URI getRequestUri() { return URI.create(rawPath); }
        @Override public UriBuilder getRequestUriBuilder() { return UriBuilder.fromPath(rawPath); }
        @Override public URI getAbsolutePath() { return URI.create(rawPath); }
        @Override public UriBuilder getAbsolutePathBuilder() { return UriBuilder.fromPath(rawPath); }
        @Override public URI getBaseUri() { return URI.create("/"); }
        @Override public UriBuilder getBaseUriBuilder() { return UriBuilder.fromPath("/"); }
        @Override public MultivaluedMap<String, String> getPathParameters() { return new MultivaluedHashMap<>(); }
        @Override public MultivaluedMap<String, String> getPathParameters(boolean decode) { return new MultivaluedHashMap<>(); }
        @Override public MultivaluedMap<String, String> getQueryParameters() { return new MultivaluedHashMap<>(); }
        @Override public MultivaluedMap<String, String> getQueryParameters(boolean decode) { return new MultivaluedHashMap<>(); }
        @Override public List<String> getMatchedURIs() { return List.of(); }
        @Override public List<String> getMatchedURIs(boolean decode) { return List.of(); }
        @Override public List<Object> getMatchedResources() { return List.of(); }
        @Override public URI resolve(URI uri) { return uri; }
        @Override public URI relativize(URI uri) { return uri; }
    }
}
