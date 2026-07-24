# Quantum System Management

`quantum-system-management` contains endpoint-free implementations and API
payloads used by the shared system control plane, including authentication,
tenant provisioning, onboarding, application admission, policy-file loading,
and shared REST DTO/service packages.

`quantum-system-rest` adds the server-facing JAX-RS resources on top of this
module. Applications that call the control plane through generated SDKs do not
need either server artifact.

This artifact does not expose REST endpoints.
