package com.e2eq.framework.rest.usage;

import jakarta.annotation.Priority;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import io.quarkus.security.Authenticated;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Automatic post-authentication endpoint governance and response observation. */
@Provider
@Priority(UsageGovernanceFilter.USAGE_GOVERNANCE_PRIORITY)
public class UsageGovernanceFilter implements ContainerRequestFilter, ContainerResponseFilter {

    /**
     * Request filters run in ascending order and response filters in descending
     * order, placing governance after security setup and before security cleanup.
     */
    public static final int USAGE_GOVERNANCE_PRIORITY = Priorities.USER + 100;

    private static final Logger LOG = Logger.getLogger(UsageGovernanceFilter.class);
    private static final String REQUEST_STATE = UsageGovernanceFilter.class.getName() + ".state";

    @Context
    ResourceInfo resourceInfo;

    private final UsageGovernanceConfig config;
    private final UsageEndpointIdentityResolver endpointResolver;
    private final UsagePrincipalIdentityResolver principalResolver;
    private final UsagePolicySource policySource;
    private final UsageBucketRegistry buckets;
    private final Iterable<UsageObserver> observers;
    private final LongSupplier ticker;

    @Inject
    public UsageGovernanceFilter(
            UsageGovernanceConfig config,
            UsageEndpointIdentityResolver endpointResolver,
            UsagePrincipalIdentityResolver principalResolver,
            UsagePolicySource policySource,
            UsageBucketRegistry buckets,
            Instance<UsageObserver> observers) {
        this(config, endpointResolver, principalResolver, policySource, buckets, observers,
                System::nanoTime);
    }

    UsageGovernanceFilter(
            UsageGovernanceConfig config,
            UsageEndpointIdentityResolver endpointResolver,
            UsagePrincipalIdentityResolver principalResolver,
            UsagePolicySource policySource,
            UsageBucketRegistry buckets,
            Iterable<UsageObserver> observers,
            LongSupplier ticker) {
        this.config = Objects.requireNonNull(config, "config");
        this.endpointResolver = Objects.requireNonNull(endpointResolver, "endpointResolver");
        this.principalResolver = Objects.requireNonNull(principalResolver, "principalResolver");
        this.policySource = Objects.requireNonNull(policySource, "policySource");
        this.buckets = Objects.requireNonNull(buckets, "buckets");
        this.observers = Objects.requireNonNull(observers, "observers");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
    }

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        if (!config.active() || isPermitAllEndpoint()) {
            return;
        }

        long startedNanos = ticker.getAsLong();
        String method = requestContext.getMethod();
        long requestBytes = contentLength(requestContext.getHeaderString(HttpHeaders.CONTENT_LENGTH));
        UsageEndpointIdentity endpoint = null;
        UsagePrincipalIdentity principal = null;
        UsageRequest request = null;

        try {
            endpoint = endpointResolver.resolve(resourceInfo, requestContext);
            principal = principalResolver.resolve();
            request = new UsageRequest(endpoint, principal, method, requestBytes);

            UsagePolicy policy = resolvePolicy(request).orElse(null);
            if (policy == null) {
                if (config.mode() == UsageGovernanceMode.ENFORCE && !config.allowUnmatchedEndpoints()) {
                    throw new UsageEnforcementStateException(
                            UsageEnforcementStateException.POLICY_NOT_FOUND,
                            "No usage policy matched the trusted endpoint and principal identity");
                }
                setState(requestContext, startedNanos, method, requestBytes, endpoint, principal,
                        UsageAdmissionDecision.bypassed());
                return;
            }
            if (!policy.appliesTo(endpoint)) {
                throw new UsageEnforcementStateException(
                        UsageEnforcementStateException.POLICY_SOURCE_UNAVAILABLE,
                        "UsagePolicySource returned a policy that does not apply to the request endpoint");
            }

            UsageBucketRegistry.AccessResult result;
            try {
                result = buckets.tryConsume(policy, request);
            } catch (RuntimeException exception) {
                throw new UsageEnforcementStateException(
                        UsageEnforcementStateException.BUCKET_CAPACITY_UNAVAILABLE,
                        "Usage bucket state is unavailable", exception);
            }

            if (result.outcome() == UsageBucketRegistry.Outcome.CAPACITY_BYPASSED) {
                setState(requestContext, startedNanos, method, requestBytes, endpoint, principal,
                        UsageAdmissionDecision.capacityBypassed(policy));
                return;
            }
            if (result.outcome() == UsageBucketRegistry.Outcome.CONSUMED) {
                setState(requestContext, startedNanos, method, requestBytes, endpoint, principal,
                        UsageAdmissionDecision.admitted(policy, result.remainingTokens()));
                return;
            }

            UsageAdmissionDecision decision = UsageAdmissionDecision.exhausted(
                    config.mode(), policy, result.retryNanos());
            setState(requestContext, startedNanos, method, requestBytes, endpoint, principal, decision);
            if (config.mode() == UsageGovernanceMode.ENFORCE) {
                requestContext.abortWith(rateLimited(endpoint, decision.retryAfter()));
            }
        } catch (UsageEnforcementStateException exception) {
            handleStateError(
                    requestContext, startedNanos, method, requestBytes, endpoint, principal, exception);
        } catch (RuntimeException exception) {
            handleStateError(
                    requestContext,
                    startedNanos,
                    method,
                    requestBytes,
                    endpoint,
                    principal,
                    new UsageEnforcementStateException(
                            UsageEnforcementStateException.POLICY_SOURCE_UNAVAILABLE,
                            "Usage policy evaluation failed", exception));
        }
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext)
            throws IOException {
        Object value = requestContext.getProperty(REQUEST_STATE);
        if (!(value instanceof RequestState state)) {
            return;
        }
        long elapsed = Math.max(0, ticker.getAsLong() - state.startedNanos());
        long responseBytes = responseContext.getLength();
        UsageObservation observation = new UsageObservation(
                state.endpoint() == null ? null : state.endpoint().canonicalName(),
                state.principal() == null ? null : state.principal().tenantId(),
                state.principal() == null ? null : state.principal().subjectId(),
                state.method(),
                responseContext.getStatus(),
                Duration.ofNanos(elapsed),
                state.requestBytes(),
                responseBytes < 0 ? -1 : responseBytes,
                state.decision());
        for (UsageObserver observer : observers) {
            try {
                observer.observe(observation);
            } catch (RuntimeException exception) {
                // Observation is a post-response OSS seam, not admission authority.
                LOG.errorf(exception, "UsageObserver %s failed", observer.getClass().getName());
            }
        }
    }

    private Optional<UsagePolicy> resolvePolicy(UsageRequest request) {
        try {
            Optional<UsagePolicy> policy = policySource.policyFor(request);
            if (policy == null) {
                throw new IllegalStateException("UsagePolicySource returned null instead of Optional");
            }
            return policy;
        } catch (RuntimeException exception) {
            throw new UsageEnforcementStateException(
                    UsageEnforcementStateException.POLICY_SOURCE_UNAVAILABLE,
                    "Usage policy source is unavailable", exception);
        }
    }

    private boolean isPermitAllEndpoint() {
        if (resourceInfo == null) {
            return false;
        }
        return isPermitAllEndpoint(resourceInfo.getResourceMethod(), resourceInfo.getResourceClass());
    }

    static boolean isPermitAllEndpoint(Method method, Class<?> resourceClass) {
        if (method != null) {
            if (method.isAnnotationPresent(PermitAll.class)) {
                return true;
            }
            if (method.isAnnotationPresent(RolesAllowed.class)
                    || method.isAnnotationPresent(DenyAll.class)
                    || method.isAnnotationPresent(Authenticated.class)) {
                return false;
            }
        }
        return resourceClass != null && resourceClass.isAnnotationPresent(PermitAll.class);
    }

    private void handleStateError(
            ContainerRequestContext requestContext,
            long startedNanos,
            String method,
            long requestBytes,
            UsageEndpointIdentity endpoint,
            UsagePrincipalIdentity principal,
            UsageEnforcementStateException exception) {
        setState(requestContext, startedNanos, method, requestBytes, endpoint, principal,
                UsageAdmissionDecision.enforcementError(exception.code()));
        if (config.mode() == UsageGovernanceMode.ENFORCE) {
            LOG.errorf(exception, "Usage governance failed closed: code=%s endpoint=%s",
                    exception.code(), endpoint == null ? "unresolved" : endpoint.canonicalName());
            requestContext.abortWith(enforcementUnavailable(endpoint, exception.code()));
        } else {
            LOG.warnf(exception, "Usage governance observation state unavailable: code=%s endpoint=%s",
                    exception.code(), endpoint == null ? "unresolved" : endpoint.canonicalName());
        }
    }

    private static void setState(
            ContainerRequestContext context,
            long startedNanos,
            String method,
            long requestBytes,
            UsageEndpointIdentity endpoint,
            UsagePrincipalIdentity principal,
            UsageAdmissionDecision decision) {
        context.setProperty(REQUEST_STATE,
                new RequestState(startedNanos, method, requestBytes, endpoint, principal, decision));
    }

    private static Response rateLimited(UsageEndpointIdentity endpoint, Duration retryAfter) {
        long seconds = Math.max(1, divideRoundingUp(retryAfter.toNanos(), TimeUnit.SECONDS.toNanos(1)));
        UsageGovernanceError error = new UsageGovernanceError(
                Response.Status.TOO_MANY_REQUESTS.getStatusCode(),
                "USAGE_LIMIT_EXCEEDED",
                "Request usage policy exceeded; retry after the indicated delay",
                endpoint.canonicalName());
        return Response.status(Response.Status.TOO_MANY_REQUESTS)
                .type(MediaType.APPLICATION_JSON_TYPE.withCharset(StandardCharsets.UTF_8.name()))
                .header(HttpHeaders.RETRY_AFTER, seconds)
                .entity(error)
                .build();
    }

    private static Response enforcementUnavailable(UsageEndpointIdentity endpoint, String code) {
        UsageGovernanceError error = new UsageGovernanceError(
                Response.Status.SERVICE_UNAVAILABLE.getStatusCode(),
                code,
                "Usage enforcement state is unavailable; the request was not admitted",
                endpoint == null ? null : endpoint.canonicalName());
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .type(MediaType.APPLICATION_JSON_TYPE.withCharset(StandardCharsets.UTF_8.name()))
                .entity(error)
                .build();
    }

    private static long contentLength(String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed < 0 ? -1 : parsed;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static long divideRoundingUp(long numerator, long denominator) {
        if (numerator <= 0) {
            return 0;
        }
        return 1L + (numerator - 1L) / denominator;
    }

    private record RequestState(
            long startedNanos,
            String method,
            long requestBytes,
            UsageEndpointIdentity endpoint,
            UsagePrincipalIdentity principal,
            UsageAdmissionDecision decision) {
    }
}
