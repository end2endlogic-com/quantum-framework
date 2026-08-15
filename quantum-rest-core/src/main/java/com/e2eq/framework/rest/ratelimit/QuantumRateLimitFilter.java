package com.e2eq.framework.rest.ratelimit;

import com.e2eq.framework.rest.models.RestError;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Global pre-authentication request throttling for Quantum REST applications.
 *
 * <p>Pre-matching priority protects the JAX-RS request path, including the
 * framework security filter and unmatched routes. Quarkus HTTP-layer
 * authentication runs before JAX-RS and requires edge or HTTP-layer
 * throttling. Because this filter runs before JAX-RS authentication, it uses a
 * trusted network identity; user-level policy belongs in a post-authentication
 * endpoint policy layer.</p>
 */
@Provider
@PreMatching
@Priority(Priorities.AUTHENTICATION - 100)
public class QuantumRateLimitFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(QuantumRateLimitFilter.class);
    static final String FORWARDED_FOR = "X-Forwarded-For";

    @Inject
    RateLimitConfig config;

    @Inject
    RateLimitClientIdentity clientIdentity;

    @Inject
    RateLimitBucketRegistry buckets;

    @Inject
    RateLimitStats stats;

    @Inject
    RoutingContext routingContext;

    private Function<ContainerRequestContext, String> testPeerAddress;

    public QuantumRateLimitFilter() {
        // CDI constructor.
    }

    QuantumRateLimitFilter(
            RateLimitConfig config,
            RateLimitClientIdentity clientIdentity,
            RateLimitBucketRegistry buckets,
            RateLimitStats stats,
            Function<ContainerRequestContext, String> peerAddress) {
        this.config = config;
        this.clientIdentity = clientIdentity;
        this.buckets = buckets;
        this.stats = stats;
        this.testPeerAddress = peerAddress;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        if (!config.active()) {
            return;
        }

        RateLimitClientIdentity.Resolution resolution = clientIdentity.resolve(
                requestContext.getHeaderString(FORWARDED_FOR),
                peerAddress(requestContext),
                config);
        if (resolution.forwardedResolutionFailed()) {
            if (stats.recordForwardedResolutionFailed()) {
                LOG.warnf(
                        "Trusted rate-limit peer supplied an unresolved forwarded chain: observed %d hop(s), "
                                + "configured trusted-proxy-hops=%d. Requests from trusted peers require a valid chain.",
                        resolution.observedForwardedHops(),
                        config.trustedProxyHops());
            }
            requestContext.abortWith(invalidForwardedIdentity());
            return;
        }
        RateLimitBucketRegistry.AccessResult result = buckets.tryConsume(resolution.key());
        if (result.overflow()) {
            stats.recordOverflowAssignment();
        }

        if (result.probe().isConsumed()) {
            stats.recordAllowed();
            return;
        }
        if (config.mode() == RateLimitMode.MONITOR) {
            stats.recordWouldBeRejected();
            return;
        }

        stats.recordRejected();
        requestContext.abortWith(rateLimited(result.probe().getNanosToWaitForRefill()));
    }

    private String peerAddress(ContainerRequestContext requestContext) {
        if (testPeerAddress != null) {
            return testPeerAddress.apply(requestContext);
        }
        if (routingContext == null || routingContext.request() == null
                || routingContext.request().remoteAddress() == null) {
            return null;
        }
        return routingContext.request().remoteAddress().hostAddress();
    }

    private static Response rateLimited(long nanosToWaitForRefill) {
        long retryAfterSeconds = Math.max(
                1L,
                divideRoundingUp(nanosToWaitForRefill, TimeUnit.SECONDS.toNanos(1)));
        RestError error = RestError.builder()
                .status(Response.Status.TOO_MANY_REQUESTS.getStatusCode())
                .reasonCode(Response.Status.TOO_MANY_REQUESTS.getStatusCode())
                .statusMessage("Too Many Requests")
                .reasonMessage("Request rate limit exceeded; retry after the indicated delay")
                .build();
        return Response.status(Response.Status.TOO_MANY_REQUESTS)
                .type(MediaType.APPLICATION_JSON_TYPE.withCharset(StandardCharsets.UTF_8.name()))
                .header(HttpHeaders.RETRY_AFTER, retryAfterSeconds)
                .entity(error)
                .build();
    }

    private static Response invalidForwardedIdentity() {
        RestError error = RestError.builder()
                .status(Response.Status.BAD_REQUEST.getStatusCode())
                .reasonCode(Response.Status.BAD_REQUEST.getStatusCode())
                .statusMessage("Bad Request")
                .reasonMessage("Trusted proxy supplied an invalid or incomplete forwarded client identity")
                .build();
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON_TYPE.withCharset(StandardCharsets.UTF_8.name()))
                .entity(error)
                .build();
    }

    private static long divideRoundingUp(long numerator, long denominator) {
        if (numerator <= 0) {
            return 0;
        }
        return 1L + (numerator - 1L) / denominator;
    }
}
