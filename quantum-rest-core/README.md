# Quantum REST Core

`quantum-rest-core` contains reusable server-side REST infrastructure without
registering a concrete application endpoint. It includes:

- `com.e2eq.framework.rest.core.BaseResource`
- CSV import and export support
- import profiles, processors, calculators, and extension SPIs
- shared query projection conversion

New endpoint modules should depend on this artifact and extend
`com.e2eq.framework.rest.core.BaseResource`.

Applications already extending
`com.e2eq.framework.rest.resources.BaseResource` through `quantum-framework`
remain compatible. The framework keeps that historical type as a bridge to the
new implementation.

This is server-side infrastructure. Cross-service consumers should use SDKs
generated from the producing service's OpenAPI contract rather than depend on
this module.
