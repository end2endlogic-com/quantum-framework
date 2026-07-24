# Quantum Bootstrap REST

`quantum-bootstrap-rest` is the opt-in system control-plane adapter for
bootstrap-pack discovery, validation, application, and run history. It owns
the existing `/admin/bootstrap-packs` endpoint family.

Only the system-management service should select this artifact. Applications
that merely contribute bootstrap packs should depend on
`quantum-bootstrap-core`, which contains no JAX-RS resources.

The Java package and HTTP paths are unchanged from their former location in
`quantum-framework`.
