# Quantum Security Runtime

`quantum-security-runtime` contains the endpoint-free HTTP security runtime:
authorization filters, realm resolution, role augmentation, model mapping,
and field-policy response handling.

Server REST modules select this artifact when they need Quantum request
security. Client applications using generated SDKs should not depend on it.

This artifact does not expose REST endpoints.
