package com.e2eq.framework.rest.usage;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UsageGovernanceFilterTest {

    @Test
    void enforceRejectsWithTyped429AndObservesRequestResponseMeasurements() throws Exception {
        Fixture fixture = fixture(UsageGovernanceMode.ENFORCE, request -> Optional.of(fixturePolicy()));
        RequestStub admitted = new RequestStub("POST", "7");

        fixture.filter.filter(admitted.context());
        fixture.time.advance(Duration.ofMillis(25));
        fixture.filter.filter(admitted.context(), response(201, 12));

        assertNull(admitted.aborted);
        assertEquals(1, fixture.observations.size());
        UsageObservation first = fixture.observations.get(0);
        assertEquals(UsageAdmissionDisposition.ADMITTED, first.admission().disposition());
        assertEquals(201, first.responseStatus());
        assertEquals(Duration.ofMillis(25), first.latency());
        assertEquals(7, first.requestBytes());
        assertEquals(12, first.responseBytes());
        assertEquals("tenant-a", first.tenantId());
        assertEquals("subject-a", first.subjectId());

        RequestStub rejected = new RequestStub("POST", "3");
        fixture.filter.filter(rejected.context());

        assertEquals(429, rejected.aborted.getStatus());
        assertEquals("10", rejected.aborted.getHeaderString(HttpHeaders.RETRY_AFTER));
        UsageGovernanceError error = (UsageGovernanceError) rejected.aborted.getEntity();
        assertEquals("USAGE_LIMIT_EXCEEDED", error.code());
        fixture.filter.filter(rejected.context(), response(429, -1));
        assertEquals(UsageAdmissionDisposition.REJECTED,
                fixture.observations.get(1).admission().disposition());
    }

    @Test
    void observeRecordsWouldRejectWithoutBlocking() throws Exception {
        Fixture fixture = fixture(UsageGovernanceMode.OBSERVE, request -> Optional.of(fixturePolicy()));
        fixture.filter.filter(new RequestStub("GET", null).context());
        RequestStub second = new RequestStub("GET", null);

        fixture.filter.filter(second.context());
        fixture.filter.filter(second.context(), response(200, 2));

        assertNull(second.aborted);
        assertEquals(UsageAdmissionDisposition.WOULD_REJECT,
                fixture.observations.get(0).admission().disposition());
    }

    @Test
    void enforceFailsClosedWhenPolicyStateIsUnavailable() throws Exception {
        Fixture fixture = fixture(UsageGovernanceMode.ENFORCE, request -> {
            throw new IllegalStateException("policy backend unavailable");
        });
        RequestStub request = new RequestStub("GET", null);

        fixture.filter.filter(request.context());

        assertEquals(503, request.aborted.getStatus());
        UsageGovernanceError error = (UsageGovernanceError) request.aborted.getEntity();
        assertEquals(UsageEnforcementStateException.POLICY_SOURCE_UNAVAILABLE, error.code());
    }

    @Test
    void enforceCanExplicitlyAllowOrRejectMappedEndpointsWithoutAPolicy() throws Exception {
        Fixture allowed = fixture(UsageGovernanceMode.ENFORCE, request -> Optional.empty(), true);
        RequestStub allowedRequest = new RequestStub("GET", null);
        allowed.filter.filter(allowedRequest.context());
        allowed.filter.filter(allowedRequest.context(), response(200, 0));

        assertNull(allowedRequest.aborted);
        assertEquals(UsageAdmissionDisposition.BYPASSED,
                allowed.observations.get(0).admission().disposition());

        Fixture denied = fixture(UsageGovernanceMode.ENFORCE, request -> Optional.empty(), false);
        RequestStub deniedRequest = new RequestStub("GET", null);
        denied.filter.filter(deniedRequest.context());

        assertEquals(503, deniedRequest.aborted.getStatus());
        UsageGovernanceError error = (UsageGovernanceError) deniedRequest.aborted.getEntity();
        assertEquals(UsageEnforcementStateException.POLICY_NOT_FOUND, error.code());
    }

    @Test
    void enforceAdmitsAndObservesRegistryCapacityOverflow() throws Exception {
        UsageGovernanceConfig config = new UsageGovernanceConfig(Map.of(
                UsageGovernanceConfig.MODE, UsageGovernanceMode.ENFORCE.name(),
                UsageGovernanceConfig.OPENAPI_OPERATION_ID_STRATEGY, "METHOD",
                UsageGovernanceConfig.POLICY_REQUEST_LIMIT, "1",
                UsageGovernanceConfig.POLICY_REFILL_PERIOD, "PT1M",
                UsageGovernanceConfig.MAX_TRACKED_KEYS, "1",
                UsageGovernanceConfig.IDLE_TIMEOUT, "PT1M"));
        UsageBucketRegistryTest.FixedTime time = new UsageBucketRegistryTest.FixedTime();
        UsageBucketRegistry buckets = new UsageBucketRegistry(1, Duration.ofMinutes(1).toNanos(), time);
        List<UsageObservation> observations = new ArrayList<>();
        UsageEndpointIdentity endpoint = new UsageEndpointIdentity("sales", "order", "createOrder");
        java.util.concurrent.atomic.AtomicReference<UsagePrincipalIdentity> current =
                new java.util.concurrent.atomic.AtomicReference<>(
                        new UsagePrincipalIdentity("tenant-a", "subject-a"));
        UsageGovernanceFilter filter = new UsageGovernanceFilter(
                config,
                new FixedEndpointResolver(endpoint),
                new UsagePrincipalIdentityResolver() {
                    @Override
                    public UsagePrincipalIdentity resolve() {
                        return current.get();
                    }
                },
                request -> Optional.of(fixturePolicy()),
                buckets,
                List.of(observations::add),
                time);

        RequestStub first = new RequestStub("GET", null);
        filter.filter(first.context());
        filter.filter(first.context(), response(200, 0));
        current.set(new UsagePrincipalIdentity("tenant-b", "subject-b"));
        RequestStub second = new RequestStub("GET", null);
        filter.filter(second.context());
        filter.filter(second.context(), response(200, 0));

        assertNull(second.aborted);
        assertEquals(UsageAdmissionDisposition.CAPACITY_BYPASSED,
                observations.get(1).admission().disposition());
        assertEquals("tenant-b", observations.get(1).tenantId());
    }

    private static Fixture fixture(UsageGovernanceMode mode, UsagePolicySource source) {
        return fixture(mode, source, true);
    }

    private static Fixture fixture(
            UsageGovernanceMode mode,
            UsagePolicySource source,
            boolean allowUnmatchedEndpoints) {
        UsageGovernanceConfig config = new UsageGovernanceConfig(Map.of(
                UsageGovernanceConfig.MODE, mode.name(),
                UsageGovernanceConfig.OPENAPI_OPERATION_ID_STRATEGY, "METHOD",
                UsageGovernanceConfig.ALLOW_UNMATCHED_ENDPOINTS, Boolean.toString(allowUnmatchedEndpoints),
                UsageGovernanceConfig.POLICY_REQUEST_LIMIT, "1",
                UsageGovernanceConfig.POLICY_REFILL_PERIOD, "PT10S",
                UsageGovernanceConfig.MAX_TRACKED_KEYS, "10",
                UsageGovernanceConfig.IDLE_TIMEOUT, "PT1M"));
        UsageBucketRegistryTest.FixedTime time = new UsageBucketRegistryTest.FixedTime();
        UsageBucketRegistry buckets = new UsageBucketRegistry(
                10, Duration.ofMinutes(1).toNanos(), time);
        UsageEndpointIdentity endpoint = new UsageEndpointIdentity("sales", "order", "createOrder");
        UsagePrincipalIdentity principal = new UsagePrincipalIdentity("tenant-a", "subject-a");
        UsageEndpointIdentityResolver endpointResolver = new FixedEndpointResolver(endpoint);
        UsagePrincipalIdentityResolver principalResolver = new UsagePrincipalIdentityResolver() {
            @Override
            public UsagePrincipalIdentity resolve() {
                return principal;
            }
        };
        List<UsageObservation> observations = new ArrayList<>();
        UsageGovernanceFilter filter = new UsageGovernanceFilter(
                config,
                endpointResolver,
                principalResolver,
                source,
                buckets,
                List.of(observations::add),
                time);
        return new Fixture(filter, time, observations);
    }

    private static UsagePolicy fixturePolicy() {
        return new UsagePolicy(
                "test-policy", "1", 1, Duration.ofSeconds(10), java.util.Set.of(UsagePolicy.ALL_ENDPOINTS));
    }

    private static final class FixedEndpointResolver extends UsageEndpointIdentityResolver {
        private final UsageEndpointIdentity endpoint;

        private FixedEndpointResolver(UsageEndpointIdentity endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public UsageEndpointIdentity resolve(
                jakarta.ws.rs.container.ResourceInfo resourceInfo,
                ContainerRequestContext requestContext) {
            return endpoint;
        }
    }

    private static ContainerResponseContext response(int status, int length) {
        return (ContainerResponseContext) Proxy.newProxyInstance(
                ContainerResponseContext.class.getClassLoader(),
                new Class<?>[]{ContainerResponseContext.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getStatus" -> status;
                    case "getLength" -> length;
                    default -> primitiveDefault(method.getReturnType());
                });
    }

    private record Fixture(
            UsageGovernanceFilter filter,
            UsageBucketRegistryTest.FixedTime time,
            List<UsageObservation> observations) {
    }

    private static final class RequestStub {
        private final String method;
        private final String contentLength;
        private final Map<String, Object> properties = new HashMap<>();
        private Response aborted;
        private final ContainerRequestContext context;

        private RequestStub(String method, String contentLength) {
            this.method = method;
            this.contentLength = contentLength;
            this.context = (ContainerRequestContext) Proxy.newProxyInstance(
                    ContainerRequestContext.class.getClassLoader(),
                    new Class<?>[]{ContainerRequestContext.class},
                    (proxy, invoked, arguments) -> switch (invoked.getName()) {
                        case "getMethod" -> this.method;
                        case "getHeaderString" -> HttpHeaders.CONTENT_LENGTH.equals(arguments[0])
                                ? this.contentLength
                                : null;
                        case "setProperty" -> {
                            properties.put((String) arguments[0], arguments[1]);
                            yield null;
                        }
                        case "getProperty" -> properties.get(arguments[0]);
                        case "getPropertyNames" -> properties.keySet();
                        case "removeProperty" -> properties.remove(arguments[0]);
                        case "abortWith" -> {
                            aborted = (Response) arguments[0];
                            yield null;
                        }
                        default -> primitiveDefault(invoked.getReturnType());
                    });
        }

        private ContainerRequestContext context() {
            return context;
        }
    }

    private static Object primitiveDefault(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}
