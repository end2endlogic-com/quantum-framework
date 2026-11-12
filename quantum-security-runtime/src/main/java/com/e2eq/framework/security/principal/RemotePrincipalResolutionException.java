package com.e2eq.framework.security.principal;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A remote principal resolution that did not produce a principal: Auth's typed refusal, passed
 * through with Auth's status and code, or {@code PRINCIPAL_RESOLUTION_UNAVAILABLE} (503) when
 * Auth could not answer. The response body is {@code {"code", "message"}}, the shape of Auth's
 * {@code PrincipalContextProblem}; the exception message carries the detail for logs only.
 */
public class RemotePrincipalResolutionException extends WebApplicationException {

    public static final String UNAVAILABLE = "PRINCIPAL_RESOLUTION_UNAVAILABLE";
    static final String UNAVAILABLE_MESSAGE = "The identity could not be resolved; try again later";

    private final String code;
    private final String publicMessage;

    public RemotePrincipalResolutionException(int status, String code, String publicMessage, String detail) {
        super(detail, null, response(status, code, publicMessage));
        this.code = Objects.requireNonNull(code, "code");
        this.publicMessage = publicMessage;
    }

    /** Auth unreachable, or its answer unusable, and no unexpired resolution held: fail closed. */
    public static RemotePrincipalResolutionException unavailable(String detail) {
        return new RemotePrincipalResolutionException(503, UNAVAILABLE, UNAVAILABLE_MESSAGE, detail);
    }

    public String code() {
        return code;
    }

    public int status() {
        return getResponse().getStatus();
    }

    public String publicMessage() {
        return publicMessage;
    }

    private static Response response(int status, String code, String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .header("Cache-Control", "no-store")
                .entity(body)
                .build();
    }
}
