# Quantum Seed REST

`quantum-seed-rest` is the opt-in system control-plane adapter for Quantum
seed-pack administration. It owns the existing `/admin/seeds` endpoint family
and depends on the endpoint-free `quantum-seed-core` runtime.

Only the system-management service should select this artifact. Application
backends and SDK-only consumers should omit it so they do not expose seed
administration or acquire its persistence runtime.

The Java package and HTTP paths are unchanged from their former location in
`quantum-framework`.
