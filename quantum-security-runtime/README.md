# Quantum Security Runtime

`quantum-security-runtime` contains the endpoint-free HTTP security runtime:
authorization filters, realm resolution, role augmentation, model mapping,
and field-policy response handling.

Server REST modules select this artifact when they need Quantum request
security. Client applications using generated SDKs should not depend on it.

This artifact does not expose REST endpoints.

Request migration checks reuse `SystemRealmOwnership`: embedded deployments check
the system schema; remote deployments leave that schema to the control-plane owner.
Application schema checks still fail closed in either mode. This is independent of
authentication and admission and does not grant access to a remote system database.
